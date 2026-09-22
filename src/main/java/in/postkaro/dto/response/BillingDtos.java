package in.postkaro.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class BillingDtos {

	/** GET /api/v1/billing/status */
	public record BillingStatus(String plan, String status, boolean active, Instant trialEndsAt,
			Instant currentPeriodEnd, long daysLeft, int monthlyCredits, int topupCredits, int totalCredits,
			int usedThisPeriod, int accountLimit, List<PlanOption> plans, Map<String, Integer> costs) {
	}

	public record PlanOption(String code, int priceInr, int monthlyCredits, int accountLimit) {
	}

	/** GET /api/v1/billing/transactions */
	public record CreditEntry(UUID id, String action, String label, int amount, int balanceAfter, String note,
			Instant createdAt) {
	}

	/** POST /api/v1/billing/request-upgrade */
	public record UpgradeRequestBody(String plan, Integer credits, String message) {
	}

	public record UpgradeRequestView(UUID id, String kind, String plan, int credits, String status, Instant createdAt) {
	}
}