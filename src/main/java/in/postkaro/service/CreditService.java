package in.postkaro.service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import in.postkaro.entity.CreditTransaction;
import in.postkaro.entity.Subscription;
import in.postkaro.entity.User;
import in.postkaro.enums.CreditAction;
import in.postkaro.enums.PlanTier;
import in.postkaro.exception.InsufficientCreditsException;
import in.postkaro.exception.SubscriptionRequiredException;
import in.postkaro.repository.CreditTransactionRepository;
import in.postkaro.repository.SubscriptionRepository;
import in.postkaro.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class CreditService {

	public static final Duration TRIAL_LENGTH = Duration.ofDays(7);

	private final SubscriptionRepository subscriptionRepository;
	private final CreditTransactionRepository txRepository;
	private final UserRepository userRepository;
	private final TransactionTemplate tx;

	public CreditService(SubscriptionRepository subscriptionRepository, CreditTransactionRepository txRepository,
			UserRepository userRepository, PlatformTransactionManager txManager) {
		this.subscriptionRepository = subscriptionRepository;
		this.txRepository = txRepository;
		this.userRepository = userRepository;
		// Each credit operation commits on its own, so the row lock is released
		// before the slow AI call starts.
		this.tx = new TransactionTemplate(txManager);
		this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	// ---------------------------------------------------------------------
	// Subscription lifecycle
	// ---------------------------------------------------------------------

	/**
	 * Returns the user's subscription, creating a 7-day trial if none exists.
	 * Called on signup, and lazily for users who signed up before billing existed.
	 */
	public Subscription getOrCreate(UUID userId) {
		return subscriptionRepository.findByUserId(userId).orElseGet(() -> {
			try {
				return tx.execute(s -> startTrial(userId));
			} catch (DataIntegrityViolationException raced) {
				// Another request created it at the same moment
				return subscriptionRepository.findByUserId(userId).orElseThrow();
			}
		});
	}

	private Subscription startTrial(UUID userId) {
		User user = userRepository.getReferenceById(userId);
		Instant now = Instant.now();
		Instant trialEnd = now.plus(TRIAL_LENGTH);

		Subscription sub = subscriptionRepository.save(Subscription.builder().user(user).plan(PlanTier.TRIAL)
				.status(Subscription.Status.TRIALING).trialEndsAt(trialEnd).currentPeriodStart(now)
				.currentPeriodEnd(trialEnd).monthlyCredits(PlanTier.TRIAL.getMonthlyCredits()).build());

		log(user, CreditAction.MONTHLY_GRANT, PlanTier.TRIAL.getMonthlyCredits(), 0, sub, null, "Free trial credits");
		return sub;
	}

	/**
	 * Starts a fresh 1-month period on the given plan and resets monthly credits.
	 * Call this from the payment webhook (or manually for now). Top-up credits are
	 * kept.
	 */
	public Subscription activatePlan(UUID userId, PlanTier plan) {
		if (!plan.isPaid()) {
			throw new IllegalArgumentException("Use a paid plan.");
		}
		getOrCreate(userId);
		return tx.execute(s -> {
			Subscription sub = subscriptionRepository.findByUserIdForUpdate(userId).orElseThrow();
			Instant now = Instant.now();

			sub.setPlan(plan);
			sub.setStatus(Subscription.Status.ACTIVE);
			sub.setTrialEndsAt(null);
			sub.setCurrentPeriodStart(now);
			sub.setCurrentPeriodEnd(now.plus(30, ChronoUnit.DAYS));

			int oldMonthly = sub.getMonthlyCredits();
			sub.setMonthlyCredits(plan.getMonthlyCredits());

			log(sub.getUser(), CreditAction.MONTHLY_GRANT, plan.getMonthlyCredits() - oldMonthly, 0, sub, null,
					plan.name() + " plan activated");
			return subscriptionRepository.save(sub);
		});
	}

	/** Adds purchased credits that never expire. */
	public Subscription addTopup(UUID userId, int credits, String note) {
		if (credits <= 0) {
			throw new IllegalArgumentException("Top-up must be positive.");
		}
		getOrCreate(userId);
		return tx.execute(s -> {
			Subscription sub = subscriptionRepository.findByUserIdForUpdate(userId).orElseThrow();
			sub.setTopupCredits(sub.getTopupCredits() + credits);
			log(sub.getUser(), CreditAction.TOPUP, 0, credits, sub, null, note);
			return subscriptionRepository.save(sub);
		});
	}

	// ---------------------------------------------------------------------
	// Access + spending
	// ---------------------------------------------------------------------

	public boolean hasAccess(UUID userId) {
		return getOrCreate(userId).hasAccess(Instant.now());
	}

	/**
	 * Deducts credits (monthly bucket first, then top-up) and returns the
	 * transaction id, which you pass to {@link #refund} if the AI call fails.
	 */
	public UUID spend(UUID userId, CreditAction action, int units) {
		if (!action.isSpend()) {
			throw new IllegalArgumentException(action + " is not a spend action.");
		}
		getOrCreate(userId);
		int cost = action.costFor(units);

		return tx.execute(s -> {
			Subscription sub = subscriptionRepository.findByUserIdForUpdate(userId).orElseThrow();

			if (!sub.hasAccess(Instant.now())) {
				throw new SubscriptionRequiredException();
			}
			if (sub.totalCredits() < cost) {
				throw new InsufficientCreditsException(cost, sub.totalCredits());
			}

			int fromMonthly = Math.min(cost, sub.getMonthlyCredits());
			int fromTopup = cost - fromMonthly;
			sub.setMonthlyCredits(sub.getMonthlyCredits() - fromMonthly);
			sub.setTopupCredits(sub.getTopupCredits() - fromTopup);
			subscriptionRepository.save(sub);

			String note = units > 1 ? action.getLabel() + " x" + units : action.getLabel();
			return log(sub.getUser(), action, -fromMonthly, -fromTopup, sub, null, note).getId();
		});
	}

	/**
	 * Puts credits from a failed generation back into the buckets they came from.
	 * Safe to call twice.
	 */
	public void refund(UUID spendTxId) {
		if (spendTxId == null) {
			return;
		}
		tx.executeWithoutResult(s -> {
			if (txRepository.existsByRefTransactionId(spendTxId)) {
				return;
			}
			CreditTransaction spent = txRepository.findById(spendTxId).orElse(null);
			if (spent == null || spent.getAmount() >= 0) {
				return;
			}
			Subscription sub = subscriptionRepository.findByUserIdForUpdate(spent.getUser().getId()).orElseThrow();
			int backMonthly = -spent.getMonthlyDelta();
			int backTopup = -spent.getTopupDelta();
			sub.setMonthlyCredits(sub.getMonthlyCredits() + backMonthly);
			sub.setTopupCredits(sub.getTopupCredits() + backTopup);
			subscriptionRepository.save(sub);

			log(sub.getUser(), CreditAction.REFUND, backMonthly, backTopup, sub, spendTxId,
					"Refund: " + spent.getAction().getLabel());
		});
	}

	/**
	 * Spend, run the AI call, refund if it throws. Use this in controllers:
	 *
	 * <pre>
	 * String caption = creditService.charge(user.getId(), CreditAction.CAPTION, 1,
	 * 		() -> aiService.generateCaption(...));
	 * </pre>
	 */
	public <T> T charge(UUID userId, CreditAction action, int units, Supplier<T> work) {
		UUID spendId = spend(userId, action, units);
		try {
			return work.get();
		} catch (RuntimeException e) {
			refund(spendId);
			throw e;
		}
	}

	// ---------------------------------------------------------------------
	// Scheduled jobs
	// ---------------------------------------------------------------------

	/** Every 15 min: mark finished trials and unpaid periods as EXPIRED. */
	@Scheduled(fixedDelay = 15 * 60 * 1000L, initialDelay = 120_000L)
	public void expireLapsed() {
		Instant now = Instant.now();

		tx.executeWithoutResult(s -> {
			for (Subscription sub : subscriptionRepository
					.findByStatusAndTrialEndsAtBefore(Subscription.Status.TRIALING, now)) {
				sub.setStatus(Subscription.Status.EXPIRED);
				sub.setMonthlyCredits(0);
			}
			// Until payments are wired in, a paid period that ends without renewal expires.
			for (Subscription sub : subscriptionRepository
					.findByStatusAndCurrentPeriodEndBefore(Subscription.Status.ACTIVE, now)) {
				sub.setStatus(Subscription.Status.EXPIRED);
				sub.setMonthlyCredits(0);
			}
		});
	}

	// ---------------------------------------------------------------------

	private CreditTransaction log(User user, CreditAction action, int monthlyDelta, int topupDelta, Subscription sub,
			UUID refTxId, String note) {
		return txRepository.save(CreditTransaction.builder().user(user).action(action).amount(monthlyDelta + topupDelta)
				.monthlyDelta(monthlyDelta).topupDelta(topupDelta).balanceAfter(sub.totalCredits())
				.refTransactionId(refTxId).note(note).build());
	}
}