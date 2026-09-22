package in.postkaro.service;

import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import in.postkaro.dto.response.AdminInsightsDtos.Insights;
import in.postkaro.dto.response.AdminInsightsDtos.Lead;
import in.postkaro.dto.response.AdminInsightsDtos.PendingRequest;
import in.postkaro.repository.AdminInsightsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminInsightsService {

	private static final int TRIAL_WARN_HOURS = 48;

	private final AdminInsightsRepository repo;
	private final NotificationService notifications;

	public Insights collect(int limit) {
		return new Insights((int) repo.totalUsers(), repo.noAccountConnected(limit), repo.connectedButNoPost(limit),
				repo.postedButNothingPublished(limit), repo.trialEndingSoon(TRIAL_WARN_HOURS, limit),
				repo.pendingRequests(limit));
	}

	/** 9:00 AM IST every day. */
	@Scheduled(cron = "0 0 9 * * *", zone = "Asia/Kolkata")
	public void dailyDigest() {
		sendDigest(false);
	}

	/** force = send even when there's nothing to act on. */
	public void sendDigest(boolean force) {
		Insights in = collect(50);

		boolean empty = in.noAccountConnected().isEmpty() && in.connectedButNoPost().isEmpty()
				&& in.postedButNothingPublished().isEmpty() && in.trialEndingSoon().isEmpty()
				&& in.pendingRequests().isEmpty();

		if (empty && !force) {
			log.info("Daily digest: nothing to report, skipping");
			return;
		}

		StringBuilder b = new StringBuilder();
		b.append("PostKaro — where your users are stuck\n");
		b.append("Total users: ").append(in.totalUsers()).append("\n");

		if (!in.pendingRequests().isEmpty()) {
			b.append("\n=== WAITING ON YOU (").append(in.pendingRequests().size()).append(") ===\n");
			for (PendingRequest r : in.pendingRequests()) {
				b.append("• ").append(r.name()).append(" — ")
						.append("PLAN".equals(r.kind()) ? r.plan() + " plan" : r.credits() + " credits").append("\n  ")
						.append(r.email());
				if (r.phone() != null) {
					b.append(" · ").append(r.phone());
				}
				b.append("\n  asked ").append(r.requestedAt()).append("\n");
				if (r.message() != null && !r.message().isBlank()) {
					b.append("  note: ").append(r.message()).append("\n");
				}
			}
		}

		section(b, "TRIAL ENDING SOON", in.trialEndingSoon());
		section(b, "POSTED BUT NOTHING PUBLISHED", in.postedButNothingPublished());
		section(b, "CONNECTED BUT NO POST", in.connectedButNoPost());
		section(b, "SIGNED UP, NO CHANNEL CONNECTED", in.noAccountConnected());

		String subject = "PostKaro daily: " + in.pendingRequests().size() + " waiting, " + in.trialEndingSoon().size()
				+ " trials ending";

		notifications.sendRaw(subject, b.toString());
	}

	private void section(StringBuilder b, String title, List<Lead> leads) {
		if (leads.isEmpty()) {
			return;
		}
		b.append("\n=== ").append(title).append(" (").append(leads.size()).append(") ===\n");
		for (Lead l : leads) {
			b.append("• ").append(l.name()).append(" — ").append(l.email());
			if (l.phone() != null && !l.phone().isBlank()) {
				b.append(" · ").append(l.phone());
			}
			b.append("\n  ").append(l.detail()).append("\n  joined ").append(l.signedUpAt()).append("\n");
		}
	}
}