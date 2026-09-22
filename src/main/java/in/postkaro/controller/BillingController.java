package in.postkaro.controller;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import in.postkaro.dto.response.ApiResponse;
import in.postkaro.dto.response.BillingDtos.BillingStatus;
import in.postkaro.dto.response.BillingDtos.CreditEntry;
import in.postkaro.dto.response.BillingDtos.PlanOption;
import in.postkaro.dto.response.BillingDtos.UpgradeRequestBody;
import in.postkaro.dto.response.BillingDtos.UpgradeRequestView;
import in.postkaro.entity.Subscription;
import in.postkaro.entity.UpgradeRequest;
import in.postkaro.entity.User;
import in.postkaro.enums.CreditAction;
import in.postkaro.enums.PlanTier;
import in.postkaro.repository.CreditTransactionRepository;
import in.postkaro.repository.UpgradeRequestRepository;
import in.postkaro.repository.UserProfileRepository;
import in.postkaro.service.CreditService;
import in.postkaro.service.NotificationService;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/billing")
@RequiredArgsConstructor
public class BillingController {

	private final CreditService creditService;
	private final CreditTransactionRepository txRepository;
	private final UpgradeRequestRepository upgradeRequests;
	private final UserProfileRepository profiles;
	private final NotificationService notifications;

	/**
	 * Plan, trial countdown, credit balance and the price list for the upgrade
	 * screen.
	 */
	@GetMapping("/status")
	public ResponseEntity<ApiResponse<BillingStatus>> status(@AuthenticationPrincipal User user) {
		Subscription sub = creditService.getOrCreate(user.getId());
		Instant now = Instant.now();

		Instant endsAt = sub.getTrialEndsAt() != null ? sub.getTrialEndsAt() : sub.getCurrentPeriodEnd();
		long daysLeft = Math.max(0, Duration.between(now, endsAt).toDays());

		int used = txRepository.sumSpentSince(user.getId(), sub.getCurrentPeriodStart());

		List<PlanOption> plans = Arrays.stream(PlanTier.values()).filter(PlanTier::isPaid)
				.map(p -> new PlanOption(p.name(), p.getPriceInr(), p.getMonthlyCredits(), p.getAccountLimit()))
				.toList();

		Map<String, Integer> costs = Arrays.stream(CreditAction.values()).filter(CreditAction::isSpend)
				.collect(Collectors.toMap(Enum::name, CreditAction::getCost));

		BillingStatus body = new BillingStatus(sub.getPlan().name(), sub.getStatus().name(), sub.hasAccess(now),
				sub.getTrialEndsAt(), sub.getCurrentPeriodEnd(), daysLeft, sub.getMonthlyCredits(),
				sub.getTopupCredits(), sub.totalCredits(), used, sub.accountLimit(), plans, costs);

		return ResponseEntity.ok(ApiResponse.ok(body, "OK"));
	}

	/** Credit history, newest first. */
	@GetMapping("/transactions")
	public ResponseEntity<ApiResponse<List<CreditEntry>>> transactions(@AuthenticationPrincipal User user,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {

		List<CreditEntry> entries = txRepository
				.findByUserIdOrderByCreatedAtDesc(user.getId(), PageRequest.of(page, Math.min(100, size)))
				.map(t -> new CreditEntry(t.getId(), t.getAction().name(), t.getAction().getLabel(), t.getAmount(),
						t.getBalanceAfter(), t.getNote(), t.getCreatedAt()))
				.toList();

		return ResponseEntity.ok(ApiResponse.ok(entries, "OK"));
	}

	/**
	 * "Request upgrade" / "Buy credits". Saves the request and emails you. Body: {
	 * "plan": "GROWTH" } or { "credits": 500 }, plus an optional "message".
	 */
	@PostMapping("/request-upgrade")
	public ResponseEntity<ApiResponse<UpgradeRequestView>> requestUpgrade(@AuthenticationPrincipal User user,
			@RequestBody UpgradeRequestBody body) {

		boolean isPlan = body.plan() != null && !body.plan().isBlank();
		boolean isTopup = body.credits() != null && body.credits() > 0;

		if (isPlan == isTopup) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Send either a plan or a credits amount.");
		}

		PlanTier plan = null;
		if (isPlan) {
			try {
				plan = PlanTier.valueOf(body.plan().trim().toUpperCase());
			} catch (IllegalArgumentException e) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown plan.");
			}
			if (!plan.isPaid()) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pick a paid plan.");
			}
		}

		// Reuse a pending request for the same thing instead of creating duplicates
		UpgradeRequest existing = upgradeRequests
				.findFirstByUserIdAndStatusOrderByCreatedAtDesc(user.getId(), UpgradeRequest.Status.PENDING)
				.orElse(null);

		if (existing != null && sameAsk(existing, plan, body.credits())) {
			return ResponseEntity.ok(ApiResponse.ok(view(existing), "We've got your request, Aman will be in touch."));
		}

		String phone = profiles.findByUserId(user.getId()).map(p -> p.getPhone()).orElse(null);

		UpgradeRequest req = upgradeRequests.save(
				UpgradeRequest.builder().user(user).kind(isPlan ? UpgradeRequest.Kind.PLAN : UpgradeRequest.Kind.TOPUP)
						.plan(plan).credits(isTopup ? body.credits() : 0).message(body.message()).phone(phone)
						.status(UpgradeRequest.Status.PENDING).build());

		notifications.upgradeRequested(req, user, creditService.getOrCreate(user.getId()));

		return ResponseEntity.ok(ApiResponse.ok(view(req), "Request sent. We'll activate it shortly."));
	}

	/**
	 * The user's own requests, so the UI can show "pending" instead of the button.
	 */
	@GetMapping("/requests")
	public ResponseEntity<ApiResponse<List<UpgradeRequestView>>> myRequests(@AuthenticationPrincipal User user) {
		List<UpgradeRequestView> list = upgradeRequests.findByUserIdOrderByCreatedAtDesc(user.getId()).stream()
				.map(this::view).toList();
		return ResponseEntity.ok(ApiResponse.ok(list, "OK"));
	}

	private boolean sameAsk(UpgradeRequest existing, PlanTier plan, Integer credits) {
		if (plan != null) {
			return existing.getKind() == UpgradeRequest.Kind.PLAN && plan == existing.getPlan();
		}
		return existing.getKind() == UpgradeRequest.Kind.TOPUP && existing.getCredits() == credits;
	}

	private UpgradeRequestView view(UpgradeRequest r) {
		return new UpgradeRequestView(r.getId(), r.getKind().name(), r.getPlan() == null ? null : r.getPlan().name(),
				r.getCredits(), r.getStatus().name(), r.getCreatedAt());
	}
}