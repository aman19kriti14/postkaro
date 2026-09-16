package in.postkaro.dto.request;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class AnalyticsDtos {

	private AnalyticsDtos() {
	}

	public record AnalyticsResponse(String periodLabel, // "LAST 30 DAYS · ALL CHANNELS"
			LocalDate from, // inclusive, IST
			LocalDate to, // inclusive, IST
			String channel, // null = all
			Stats stats, Chart chart, List<TopPost> topPosts, List<ChannelRow> byChannel, List<Suggestion> suggestions,
			boolean hasData) {
	} // false → show "no synced posts yet" states

	// ---------- stat cards ----------

	public record Stats(long reach, Double reachChangePct, // null when no previous data
			double engagementPct, Double engagementChangePts, Long followersGained, // null = not enough snapshots yet
			Long followersChange, long saves, Long savesChange) {
	}

	// ---------- chart ----------

	public record Chart(String bucket, // "day" or "week"
			List<ChartPoint> points) {
	}

	public record ChartPoint(String label, // "Wk 1" or "M"/"16 Sep"
			LocalDate start, LocalDate end, long reach, double engagementPct) {
	}

	// ---------- top posts ----------

	public record TopPost(UUID postId, String title, String format, // reel, carousel, post, story
			Set<String> channels, Instant publishedAt, String thumbnailUrl, // null when the post has no media
			long reach, double engagementPct, String note) {
	} // built from real numbers; null if nothing stands out

	// ---------- by channel ----------

	public record ChannelRow(String channel, boolean tracked, // false → "Not tracked yet"
			long posts, long reach, double engagementPct, Long followersGained) {
	}

	// ---------- what to do more of ----------

	public record Suggestion(String kind, // FORMAT, TIMING, CHANNEL
			String text, // the sentence shown
			String brief) {
	} // prefilled into AI studio on "Draft this"
}