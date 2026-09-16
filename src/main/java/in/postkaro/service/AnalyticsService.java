package in.postkaro.service;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import in.postkaro.dto.request.AnalyticsDtos.AnalyticsResponse;
import in.postkaro.dto.request.AnalyticsDtos.ChannelRow;
import in.postkaro.dto.request.AnalyticsDtos.Chart;
import in.postkaro.dto.request.AnalyticsDtos.ChartPoint;
import in.postkaro.dto.request.AnalyticsDtos.Stats;
import in.postkaro.dto.request.AnalyticsDtos.Suggestion;
import in.postkaro.dto.request.AnalyticsDtos.TopPost;
import in.postkaro.entity.Campaign;
import in.postkaro.entity.Post;
import in.postkaro.entity.PostMedia;
import in.postkaro.repository.AccountSnapshotRepository;
import in.postkaro.repository.CampaignRepository;
import in.postkaro.repository.PostMetricRepository;
import in.postkaro.repository.PostRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalyticsService {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
	private static final String[] DAY_LABELS = { "M", "T", "W", "T", "F", "S", "S" };
	private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
	private static final List<String> ALL_CHANNELS = List.of("instagram", "facebook", "linkedin", "youtube", "x");
	private static final Set<String> TRACKED = Set.of("instagram");
	private static final int MIN_POSTS = 3;
	private static final double MIN_LIFT = 1.2;

	private final PostMetricRepository metrics;
	private final PostRepository posts;
	private final CampaignRepository campaigns;
	private final AccountSnapshotRepository snapshots;

	private record Range(LocalDate from, LocalDate to, String label) {
		Instant startI() {
			return from.atStartOfDay(IST).toInstant();
		}

		Instant endI() {
			return to.plusDays(1).atStartOfDay(IST).toInstant();
		} // exclusive

		long days() {
			return ChronoUnit.DAYS.between(from, to) + 1;
		}

		Range previous() {
			long d = days();
			return new Range(from.minusDays(d), from.minusDays(1), "");
		}
	}

	// ---------- entry ----------

	public AnalyticsResponse get(UUID userId, String period, UUID campaignId, LocalDate customFrom, LocalDate customTo,
			String channelParam) {

		String channel = normaliseChannel(channelParam);
		Range range = resolve(userId, period, campaignId, customFrom, customTo);
		Range prev = range.previous();

		Stats stats = stats(userId, range, prev, channel);
		Chart chart = chart(userId, range, channel);
		List<TopPost> top = topPosts(userId, range, channel);
		List<ChannelRow> byChannel = byChannel(userId, range);
		List<Suggestion> suggestions = suggestions(userId, range, channel, byChannel);

		boolean hasData = stats.reach() > 0 || !top.isEmpty();
		String label = range.label() + " · " + (channel == null ? "ALL CHANNELS" : channel.toUpperCase());

		return new AnalyticsResponse(label, range.from(), range.to(), channel, stats, chart, top, byChannel,
				suggestions, hasData);
	}

	// ---------- period ----------

	private Range resolve(UUID userId, String period, UUID campaignId, LocalDate cFrom, LocalDate cTo) {
		LocalDate today = LocalDate.now(IST);
		String p = period == null ? "30d" : period;

		return switch (p) {
		case "7d" -> new Range(today.minusDays(6), today, "LAST 7 DAYS");
		case "campaign" -> {
			if (campaignId == null)
				throw bad("Pick a campaign");
			Campaign c = campaigns.findByIdAndUserId(campaignId, userId)
					.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Campaign not found"));
			if (c.getStartsOn() == null)
				throw bad("This campaign has no dates yet");
			LocalDate end = c.getEndsOn() == null || c.getEndsOn().isAfter(today) ? today : c.getEndsOn();
			if (end.isBefore(c.getStartsOn()))
				end = c.getStartsOn();
			yield new Range(c.getStartsOn(), end, c.getName().toUpperCase());
		}
		case "custom" -> {
			if (cFrom == null || cTo == null)
				throw bad("Pick a start and end date");
			if (cTo.isBefore(cFrom))
				throw bad("End date is before start date");
			if (ChronoUnit.DAYS.between(cFrom, cTo) > 366)
				throw bad("Pick a range under a year");
			yield new Range(cFrom, cTo.isAfter(today) ? today : cTo,
					cFrom.format(SHORT).toUpperCase() + " – " + cTo.format(SHORT).toUpperCase());
		}
		default -> new Range(today.minusDays(29), today, "LAST 30 DAYS");
		};
	}

	// ---------- stats ----------

	private Stats stats(UUID userId, Range r, Range prev, String channel) {
		long[] cur = totals(userId, r, channel);
		long[] old = totals(userId, prev, channel);

		double engNow = pct(interactions(cur), cur[0]);
		Double reachChange = old[0] > 0 ? round1((cur[0] - old[0]) * 100.0 / old[0]) : null;
		Double engChange = old[0] > 0 ? round1(engNow - pct(interactions(old), old[0])) : null;

		Long followers = null, followersChange = null;
		if (channel == null || channel.equals("instagram")) {
			followers = followersGained(userId, r);
			Long prevFollowers = followersGained(userId, prev);
			if (followers != null && prevFollowers != null)
				followersChange = followers - prevFollowers;
		}

		Long savesChange = old[5] > 0 ? cur[4] - old[4] : null;

		return new Stats(cur[0], reachChange, round1(engNow), engChange, followers, followersChange, cur[4],
				savesChange);
	}

	/** [reach, likes, comments, shares, saves, posts] */
	private long[] totals(UUID userId, Range r, String channel) {
		List<Object[]> rows = metrics.analyticsTotals(userId, r.startI(), r.endI(), channel);
		long[] out = new long[6];
		if (!rows.isEmpty() && rows.get(0) != null) {
			Object[] x = rows.get(0);
			for (int i = 0; i < 6; i++)
				out[i] = num(x[i]);
		}
		return out;
	}

	private static long interactions(long[] t) {
		return t[1] + t[2] + t[3] + t[4];
	}

	/**
	 * Sum over accounts of (last snapshot − first snapshot). Null if no account has
	 * 2+ days.
	 */
	private Long followersGained(UUID userId, Range r) {
		Map<UUID, long[]> firstLast = new HashMap<>(); // [first, last, count]
		for (Object[] s : snapshots.inRange(userId, r.from(), r.to())) {
			UUID acc = (UUID) s[0];
			long f = num(s[3]);
			long[] v = firstLast.get(acc);
			if (v == null)
				firstLast.put(acc, new long[] { f, f, 1 });
			else {
				v[1] = f;
				v[2]++;
			}
		}
		long total = 0;
		boolean any = false;
		for (long[] v : firstLast.values()) {
			if (v[2] >= 2) {
				total += v[1] - v[0];
				any = true;
			}
		}
		return any ? total : null;
	}

	// ---------- chart ----------

	private Chart chart(UUID userId, Range r, String channel) {
		Map<LocalDate, long[]> byDay = new HashMap<>();
		for (Object[] x : metrics.analyticsDaily(userId, r.startI(), r.endI(), channel)) {
			byDay.put(toDate(x[0]), new long[] { num(x[1]), num(x[2]) });
		}

		List<ChartPoint> points = new ArrayList<>();
		if (r.days() <= 14) {
			for (LocalDate d = r.from(); !d.isAfter(r.to()); d = d.plusDays(1)) {
				long[] v = byDay.getOrDefault(d, new long[2]);
				String label = r.days() <= 7 ? DAY_LABELS[d.getDayOfWeek().getValue() - 1] : d.format(SHORT);
				points.add(new ChartPoint(label, d, d, v[0], round1(pct(v[1], v[0]))));
			}
			return new Chart("day", points);
		}

		int wk = 1;
		for (LocalDate start = r.from(); !start.isAfter(r.to()); start = start.plusDays(7), wk++) {
			LocalDate end = start.plusDays(6).isAfter(r.to()) ? r.to() : start.plusDays(6);
			long reach = 0, inter = 0;
			for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
				long[] v = byDay.get(d);
				if (v != null) {
					reach += v[0];
					inter += v[1];
				}
			}
			points.add(new ChartPoint("Wk " + wk, start, end, reach, round1(pct(inter, reach))));
		}
		return new Chart("week", points);
	}

	// ---------- top posts ----------

	private List<TopPost> topPosts(UUID userId, Range r, String channel) {
		List<Object[]> rows = metrics.analyticsTopPosts(userId, r.startI(), r.endI(), channel, PageRequest.of(0, 5));
		if (rows.isEmpty())
			return List.of();

		// account-level averages for the notes
		long[] t = totals(userId, r, channel);
		double avgShareRate = t[0] == 0 ? 0 : t[3] * 100.0 / t[0];
		double avgSaveRate = t[0] == 0 ? 0 : t[4] * 100.0 / t[0];
		double avgCommentRate = t[0] == 0 ? 0 : t[2] * 100.0 / t[0];

		List<UUID> ids = rows.stream().map(x -> (UUID) x[0]).toList();
		Map<UUID, Post> byId = posts.findAllById(ids).stream().collect(Collectors.toMap(Post::getId, p -> p));

		List<TopPost> out = new ArrayList<>();
		for (Object[] x : rows) {
			Post p = byId.get((UUID) x[0]);
			if (p == null)
				continue;
			long reach = num(x[1]), inter = num(x[2]), shares = num(x[3]), saves = num(x[4]), comments = num(x[5]);

			out.add(new TopPost(p.getId(), titleOf(p), p.getFormat(), new LinkedHashSet<>(p.getChannels()),
					(Instant) x[6], thumbnail(p), reach, round1(pct(inter, reach)),
					note(reach, shares, saves, comments, avgShareRate, avgSaveRate, avgCommentRate)));
		}
		return out;
	}

	/**
	 * One factual line: whichever interaction beats the period average by the
	 * widest margin.
	 */
	private static String note(long reach, long shares, long saves, long comments, double avgShare, double avgSave,
			double avgComment) {
		if (reach <= 0)
			return null;
		record Cand(double lift, String text) {
		}
		List<Cand> c = new ArrayList<>();
		if (shares > 0 && avgShare > 0)
			c.add(new Cand((shares * 100.0 / reach) / avgShare,
					"Shared " + shares + (shares == 1 ? " time." : " times.")));
		if (saves > 0 && avgSave > 0)
			c.add(new Cand((saves * 100.0 / reach) / avgSave, "Saved " + saves + (saves == 1 ? " time." : " times.")));
		if (comments > 0 && avgComment > 0)
			c.add(new Cand((comments * 100.0 / reach) / avgComment,
					comments + (comments == 1 ? " comment." : " comments.")));
		return c.stream().filter(x -> x.lift() >= MIN_LIFT).max(Comparator.comparingDouble(Cand::lift)).map(Cand::text)
				.orElse(null);
	}

	private static String thumbnail(Post p) {
		return p.getMedia().stream().filter(m -> "image".equalsIgnoreCase(m.getType())).map(PostMedia::getUrl)
				.findFirst().orElse(null);
	}

	// ---------- by channel ----------

	private List<ChannelRow> byChannel(UUID userId, Range r) {
		Map<String, Object[]> rows = new HashMap<>();
		for (Object[] x : metrics.analyticsByChannel(userId, r.startI(), r.endI())) {
			rows.put(String.valueOf(x[0]).toLowerCase(), x);
		}
		Long igFollowers = followersGained(userId, r);

		List<ChannelRow> out = new ArrayList<>();
		for (String ch : ALL_CHANNELS) {
			Object[] x = rows.get(ch);
			long postCount = x == null ? 0 : num(x[1]);
			long reach = x == null ? 0 : num(x[2]);
			long inter = x == null ? 0 : num(x[3]);
			boolean tracked = TRACKED.contains(ch);

			if (!tracked && postCount == 0)
				continue; // don't list channels the user never posts to

			out.add(new ChannelRow(ch, tracked, postCount, tracked ? reach : 0, tracked ? round1(pct(inter, reach)) : 0,
					ch.equals("instagram") ? igFollowers : null));
		}
		out.sort(Comparator.comparingLong(ChannelRow::reach).reversed());
		return out;
	}

	// ---------- what to do more of ----------

	private List<Suggestion> suggestions(UUID userId, Range r, String channel, List<ChannelRow> byChannel) {
		List<Suggestion> out = new ArrayList<>();

		record Row(String format, int hour, long reach) {
		}
		List<Row> rows = new ArrayList<>();
		for (Object[] x : metrics.analyticsRows(userId, r.startI(), r.endI(), channel)) {
			String f = x[0] == null ? null : String.valueOf(x[0]).toLowerCase();
			int hour = ((Instant) x[1]).atZone(IST).getHour();
			rows.add(new Row(f, hour, num(x[2])));
		}

		// FORMAT: best format vs the runner-up
		Map<String, List<Long>> byFormat = new HashMap<>();
		for (Row x : rows)
			if (x.format() != null)
				byFormat.computeIfAbsent(x.format(), k -> new ArrayList<>()).add(x.reach());
		List<Map.Entry<String, Double>> ranked = byFormat.entrySet().stream()
				.filter(e -> e.getValue().size() >= MIN_POSTS)
				.map(e -> Map.entry(e.getKey(), e.getValue().stream().mapToLong(Long::longValue).average().orElse(0)))
				.sorted(Map.Entry.<String, Double>comparingByValue().reversed()).toList();
		if (ranked.size() >= 2 && ranked.get(1).getValue() > 0) {
			double ratio = ranked.get(0).getValue() / ranked.get(1).getValue();
			if (ratio >= MIN_LIFT) {
				String best = plural(ranked.get(0).getKey());
				String other = plural(ranked.get(1).getKey());
				out.add(new Suggestion("FORMAT",
						cap(best) + " reach " + String.format("%.1f", ratio) + "× your " + other + " this period. More "
								+ best + " is the easiest lift.",
						"More " + best + " for next week, built around what's been working."));
			}
		}

		// TIMING: morning vs evening
		List<Long> morning = rows.stream().filter(x -> x.hour() >= 5 && x.hour() < 12).map(Row::reach).toList();
		List<Long> evening = rows.stream().filter(x -> x.hour() >= 17).map(Row::reach).toList();
		if (morning.size() >= MIN_POSTS && evening.size() >= MIN_POSTS) {
			double m = avg(morning), e = avg(evening);
			if (e > 0 && m / e >= MIN_LIFT) {
				out.add(new Suggestion("TIMING",
						"Morning posts reached " + Math.round((m / e - 1) * 100)
								+ "% more than evening ones. Schedule the next few before noon.",
						"Posts for this week that suit a morning slot."));
			} else if (m > 0 && e / m >= MIN_LIFT) {
				out.add(new Suggestion("TIMING",
						"Evening posts reached " + Math.round((e / m - 1) * 100)
								+ "% more than morning ones. Schedule the next few after 5 pm.",
						"Posts for this week that suit an evening slot."));
			}
		}

		// CHANNEL: only once 2+ channels are actually tracked
		List<ChannelRow> tracked = byChannel.stream()
				.filter(c -> c.tracked() && c.posts() >= MIN_POSTS && c.reach() > 0).toList();
		if (channel == null && tracked.size() >= 2) {
			ChannelRow best = tracked.get(0);
			ChannelRow worst = tracked.get(tracked.size() - 1);
			double bestPer = (double) best.reach() / best.posts();
			double worstPer = (double) worst.reach() / worst.posts();
			if (worstPer > 0 && bestPer / worstPer >= MIN_LIFT) {
				out.add(new Suggestion("CHANNEL",
						label(best.channel()) + " gets " + String.format("%.1f", bestPer / worstPer)
								+ "× the reach per post of " + label(worst.channel()) + ".",
						"Ideas for " + label(best.channel()) + " this week."));
			}
		}

		return out;
	}

	// ---------- helpers ----------

	private static String normaliseChannel(String c) {
		if (c == null || c.isBlank() || c.equalsIgnoreCase("all"))
			return null;
		String x = c.trim().toLowerCase();
		if (!ALL_CHANNELS.contains(x))
			throw bad("Unknown channel");
		return x;
	}

	private static String titleOf(Post p) {
		if (p.getTitle() != null && !p.getTitle().isBlank())
			return p.getTitle();
		String cap = p.getCaption();
		if (cap == null || cap.isBlank())
			return "Untitled post";
		String line = cap.strip().split("\\R", 2)[0];
		return line.length() > 70 ? line.substring(0, 69) + "…" : line;
	}

	private static String plural(String f) {
		return switch (f) {
		case "reel" -> "reels";
		case "carousel" -> "carousels";
		case "story" -> "stories";
		default -> "single posts";
		};
	}

	private static String label(String ch) {
		return switch (ch) {
		case "instagram" -> "Instagram";
		case "facebook" -> "Facebook";
		case "linkedin" -> "LinkedIn";
		case "youtube" -> "YouTube";
		default -> "X";
		};
	}

	private static String cap(String s) {
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	private static double avg(List<Long> v) {
		return v.stream().mapToLong(Long::longValue).average().orElse(0);
	}

	private static LocalDate toDate(Object o) {
		if (o instanceof LocalDate d)
			return d;
		if (o instanceof Date d)
			return d.toLocalDate();
		return LocalDate.parse(String.valueOf(o));
	}

	private static long num(Object o) {
		return o == null ? 0 : ((Number) o).longValue();
	}

	private static double pct(long part, long whole) {
		return whole <= 0 ? 0 : part * 100.0 / whole;
	}

	private static double round1(double v) {
		return Math.round(v * 10) / 10.0;
	}

	private static ResponseStatusException bad(String m) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
	}
}