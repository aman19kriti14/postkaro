package in.postkaro.service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import in.postkaro.service.SocialContentFetcher.SocialPost;
import lombok.RequiredArgsConstructor;

/**
 * "Best time to post" from the user's OWN past posts: which weekday and hour
 * (IST) get the most engagement. Falls back to a clearly-labelled default when
 * there isn't enough data — never pretends.
 */
@Service
@RequiredArgsConstructor
public class BestTimeService {

	public record BestTime(boolean fromYourData, String day, int hour, int minute, int postsAnalyzed, String message,
			String nextDate, String nextTime) {
	}

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
	private static final int MIN_POSTS = 8; // below this the numbers are noise
	private static final int MIN_PER_BUCKET = 2; // a day/hour needs at least 2 posts to count
	private static final int FALLBACK_HOUR = 19; // common evening peak for Indian audiences
	private static final Duration CACHE_FOR = Duration.ofHours(6);

	private final SocialContentFetcher social;

	private record Cached(BestTime value, Instant at) {
	}

	private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();

	public BestTime forUser(UUID userId) {
		Cached c = cache.get(userId);
		if (c != null && c.at().isAfter(Instant.now().minus(CACHE_FOR)))
			return withFreshSlot(c.value());

		BestTime result = compute(userId);
		cache.put(userId, new Cached(result, Instant.now()));
		return result;
	}

	// ---------- the maths ----------

	private BestTime compute(UUID userId) {
		List<ZonedPost> posts = social.recentPosts(userId).stream().filter(p -> p.postedAt() != null)
				.map(p -> new ZonedPost(p, p.postedAt().atZone(IST))).toList();

		if (posts.size() < MIN_POSTS) {
			String msg = posts.isEmpty()
					? "We don't have your post history yet, so we'll use 7 pm — a common evening peak in India. This gets smarter as you post."
					: "Only " + posts.size()
							+ " posts so far — not enough to spot a pattern. We'll use 7 pm, a common evening peak in India.";
			return build(false, null, FALLBACK_HOUR, posts.size(), msg);
		}

		Optional<Integer> bestHour = best(posts, zp -> zp.time().getHour());
		Optional<DayOfWeek> bestDay = best(posts, zp -> zp.time().getDayOfWeek());

		int hour = bestHour.orElse(FALLBACK_HOUR);
		String dayName = bestDay.map(d -> d.getDisplayName(TextStyle.FULL, Locale.ENGLISH)).orElse(null);

		String when = (dayName != null ? dayName + "s" : "Most days") + " around " + prettyHour(hour);
		String msg = "Your posts get the most engagement on " + when + " (based on your last " + posts.size()
				+ " posts). Best-time posting uses this slot.";
		if (dayName == null)
			msg = "Your posts do best around " + prettyHour(hour) + " (based on your last " + posts.size()
					+ " posts). Best-time posting uses this slot.";

		return build(true, bestDay.orElse(null), hour, posts.size(), msg);
	}

	/**
	 * Bucket with the highest average engagement, ignoring buckets with < 2 posts.
	 */
	private <K> Optional<K> best(List<ZonedPost> posts, Function<ZonedPost, K> key) {
		return posts.stream().collect(Collectors.groupingBy(key)).entrySet().stream()
				.filter(e -> e.getValue().size() >= MIN_PER_BUCKET)
				.max((a, b) -> Double.compare(avg(a.getValue()), avg(b.getValue()))).map(Map.Entry::getKey);
	}

	private static double avg(List<ZonedPost> xs) {
		return xs.stream().mapToLong(zp -> zp.post().score()).average().orElse(0);
	}

	// ---------- next slot ----------

	private BestTime build(boolean fromData, DayOfWeek day, int hour, int n, String msg) {
		ZonedDateTime next = nextSlot(day, hour);
		return new BestTime(fromData, day == null ? null : day.getDisplayName(TextStyle.FULL, Locale.ENGLISH), hour, 0,
				n, msg, next.toLocalDate().toString(), String.format("%02d:%02d", hour, 0));
	}

	/** Cached result → recompute the upcoming date so it's never in the past. */
	private BestTime withFreshSlot(BestTime b) {
		DayOfWeek day = b.day() == null ? null : DayOfWeek.valueOf(b.day().toUpperCase(Locale.ROOT));
		ZonedDateTime next = nextSlot(day, b.hour());
		return new BestTime(b.fromYourData(), b.day(), b.hour(), b.minute(), b.postsAnalyzed(), b.message(),
				next.toLocalDate().toString(), b.nextTime());
	}

	/** Next time this day+hour comes around, at least 1 hour from now. */
	private static ZonedDateTime nextSlot(DayOfWeek day, int hour) {
		ZonedDateTime now = ZonedDateTime.now(IST);
		ZonedDateTime slot = LocalDateTime.of(now.toLocalDate(), java.time.LocalTime.of(hour, 0)).atZone(IST);
		if (day != null)
			slot = slot.with(TemporalAdjusters.nextOrSame(day));
		while (slot.isBefore(now.plusHours(1)))
			slot = day != null ? slot.plusWeeks(1) : slot.plusDays(1);
		return slot;
	}

	private static String prettyHour(int h) {
		int h12 = h % 12 == 0 ? 12 : h % 12;
		return h12 + (h < 12 ? " am" : " pm");
	}

	private record ZonedPost(SocialPost post, ZonedDateTime time) {
	}
}