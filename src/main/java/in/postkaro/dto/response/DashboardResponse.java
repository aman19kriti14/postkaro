package in.postkaro.dto.response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record DashboardResponse(String firstName, // "Good morning, Aarav."
		Stats stats, // 4 cards
		List<UpNextItem> upNext, // left panel
		WhatsWorking whatsWorking, // right panel
		List<CampaignCard> campaigns) {

	// ---------- stat cards ----------

	public record Stats(long scheduledNext7Days, long publishedThisMonth, long publishedDelta, // +6 vs last month
			long reach, // last 30 days
			Double reachChangePct, // +18.4 (null when no previous data)
			double engagementPct, // 5.1
			Double engagementChange // -0.3 (percentage points, null when no previous data)
	) {
	}

	// ---------- up next ----------

	public record UpNextItem(UUID id, String title, // title, or first line of caption
			Instant scheduledAt, // frontend formats "11:30 / TODAY" in IST
			Set<String> channels, String format, // reel, carousel... (for "Instagram · Reels")
			String status // SCHEDULED, NEEDS_REVIEW, DRAFT
	) {
	}

	// ---------- what's working ----------

	public record WhatsWorking(List<DayReach> week, // always 7 entries, Mon → Sun
			TopPost topByReach, // may be null
			TopPost topByEngagement // may be null
	) {
	}

	public record DayReach(LocalDate date, String label, // M, T, W...
			long reach) {
	}

	public record TopPost(UUID postId, String title, long reach, double engagementPct) {
	}

	// ---------- campaigns ----------

	public record CampaignCard(UUID id, String name, String status, // LIVE, SCHEDULED, DRAFT (computed)
			LocalDate startsOn, LocalDate endsOn, Set<String> channels, long readyPosts, // 8
			long totalPosts // of 12
	) {
	}
}