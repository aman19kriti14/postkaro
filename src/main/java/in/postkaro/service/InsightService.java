package in.postkaro.service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import in.postkaro.repository.PostMetricRepository;
import lombok.RequiredArgsConstructor;

/**
 * Real, computed facts about a user's Instagram performance. The AI may
 * reference a fact by key; the sentence shown is always written here.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InsightService {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
	private static final int LOOKBACK_DAYS = 90;
	private static final int MIN_POSTS = 3; // per group, or the fact is skipped
	private static final double MIN_LIFT = 1.2; // 20% difference before we call it out

	private final PostMetricRepository metrics;

	/** key -> fact. Keys are stable strings the AI can pick. */
	public record Fact(String key, String appliesToFormat, String text) {
	}

	private record Row(String format, int hourIst, long reach, long interactions, long saves) {
	}

	private static final class Agg {
		int n;
		long reach;
		long interactions;
		long saves;

		void add(Row r) {
			n++;
			reach += r.reach;
			interactions += r.interactions;
			saves += r.saves;
		}

		double avgReach() {
			return n == 0 ? 0 : (double) reach / n;
		}

		double engRate() {
			return reach == 0 ? 0 : interactions * 100.0 / reach;
		}

		double saveRate() {
			return reach == 0 ? 0 : saves * 100.0 / reach;
		}
	}

	public Map<String, Fact> factsFor(UUID userId) {
		List<Row> rows = load(userId);
		Map<String, Fact> out = new LinkedHashMap<>();
		if (rows.size() < MIN_POSTS)
			return out; // not enough data yet

		Map<String, Agg> byFormat = new HashMap<>();
		Agg morning = new Agg();
		Agg evening = new Agg();
		Agg all = new Agg();

		for (Row r : rows) {
			byFormat.computeIfAbsent(r.format, k -> new Agg()).add(r);
			all.add(r);
			if (r.hourIst >= 5 && r.hourIst < 12)
				morning.add(r);
			else if (r.hourIst >= 17)
				evening.add(r);
		}

		// 1. Reels vs carousels reach
		ratioFact(out, "reel_vs_carousel_reach", "reel", byFormat.get("reel"), byFormat.get("carousel"),
				(x) -> "Your reels reach " + x + "× your carousels.");
		ratioFact(out, "carousel_vs_reel_reach", "carousel", byFormat.get("carousel"), byFormat.get("reel"),
				(x) -> "Your carousels reach " + x + "× your reels.");

		// 2. Morning vs evening
		if (morning.n >= MIN_POSTS && evening.n >= MIN_POSTS && evening.avgReach() > 0) {
			double lift = morning.avgReach() / evening.avgReach();
			if (lift >= MIN_LIFT) {
				int pct = (int) Math.round((lift - 1) * 100);
				out.put("morning_beats_evening", new Fact("morning_beats_evening", null,
						"Morning posts reach " + pct + "% more than evening ones for you."));
			} else if (lift > 0 && 1 / lift >= MIN_LIFT) {
				int pct = (int) Math.round((1 / lift - 1) * 100);
				out.put("evening_beats_morning", new Fact("evening_beats_morning", null,
						"Evening posts reach " + pct + "% more than morning ones for you."));
			}
		}

		// 3. Per-format save and engagement leaders (vs the account average)
		for (Map.Entry<String, Agg> e : byFormat.entrySet()) {
			String f = e.getKey();
			Agg a = e.getValue();
			if (f == null || a.n < MIN_POSTS)
				continue;

			if (all.saveRate() > 0 && a.saveRate() / all.saveRate() >= MIN_LIFT) {
				out.put(f + "_saves", new Fact(f + "_saves", f, "Your " + plural(f) + " get saved "
						+ x1(a.saveRate() / all.saveRate()) + "× more than average."));
			}
			if (all.engRate() > 0 && a.engRate() / all.engRate() >= MIN_LIFT) {
				out.put(f + "_engagement", new Fact(f + "_engagement", f, "Your " + plural(f) + " engage at "
						+ x2(a.engRate()) + "%, above your " + x2(all.engRate()) + "% average."));
			}
		}

		return out;
	}

	/**
	 * Best fact for a card when the AI didn't pick one (or picked an invalid key).
	 */
	public Fact fallbackFor(Map<String, Fact> facts, String format) {
		return facts.values().stream().filter(f -> format.equalsIgnoreCase(f.appliesToFormat())).findFirst()
				.orElse(null);
	}

	// ---------- helpers ----------

	private List<Row> load(UUID userId) {
		Instant from = Instant.now().minus(Duration.ofDays(LOOKBACK_DAYS));
		List<Row> rows = new ArrayList<>();
		for (Object[] r : metrics.insightRows(userId, from)) {
			String format = r[0] == null ? null : String.valueOf(r[0]).toLowerCase();
			Instant at = (Instant) r[1];
			long likes = num(r[3]), comments = num(r[4]), shares = num(r[5]), saves = num(r[6]);
			rows.add(new Row(format, at.atZone(IST).getHour(), num(r[2]), likes + comments + shares + saves, saves));
		}
		return rows;
	}

	private void ratioFact(Map<String, Fact> out, String key, String format, Agg a, Agg b,
			java.util.function.Function<String, String> text) {
		if (a == null || b == null || a.n < MIN_POSTS || b.n < MIN_POSTS || b.avgReach() <= 0)
			return;
		double ratio = a.avgReach() / b.avgReach();
		if (ratio >= MIN_LIFT) {
			out.put(key, new Fact(key, format, text.apply(x1(ratio))));
		}
	}

	private static String plural(String format) {
		return switch (format) {
		case "reel" -> "reels";
		case "carousel" -> "carousels";
		case "story" -> "stories";
		default -> "single posts";
		};
	}

	private static String x1(double v) {
		return String.format("%.1f", v);
	}

	private static String x2(double v) {
		return String.format("%.1f", v);
	}

	private static long num(Object o) {
		return o == null ? 0 : ((Number) o).longValue();
	}
}