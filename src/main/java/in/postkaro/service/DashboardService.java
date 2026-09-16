package in.postkaro.service;

import java.sql.Date;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import in.postkaro.dto.response.DashboardResponse;
import in.postkaro.dto.response.DashboardResponse.CampaignCard;
import in.postkaro.dto.response.DashboardResponse.DayReach;
import in.postkaro.dto.response.DashboardResponse.Stats;
import in.postkaro.dto.response.DashboardResponse.TopPost;
import in.postkaro.dto.response.DashboardResponse.UpNextItem;
import in.postkaro.dto.response.DashboardResponse.WhatsWorking;
import in.postkaro.entity.Campaign;
import in.postkaro.entity.Post;
import in.postkaro.entity.PostMetric;
import in.postkaro.entity.User;
import in.postkaro.enums.CampaignStatus;
import in.postkaro.enums.PostStatus;
import in.postkaro.repository.CampaignRepository;
import in.postkaro.repository.PostMetricRepository;
import in.postkaro.repository.PostRepository;
import in.postkaro.repository.UserRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardService {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
	private static final String[] DAY_LABELS = { "M", "T", "W", "T", "F", "S", "S" };
	private static final long MIN_REACH_FOR_ENGAGEMENT = 100;

	private final PostRepository posts;
	private final PostMetricRepository metrics;
	private final CampaignRepository campaigns;
	private final UserRepository users;

	public DashboardResponse get(UUID userId) {
		ZonedDateTime now = ZonedDateTime.now(IST);
		LocalDate today = now.toLocalDate();

		return new DashboardResponse(firstName(userId), stats(userId, now), upNext(userId, now.toInstant()),
				whatsWorking(userId, now), activeCampaigns(userId, today));
	}

	// ---------- greeting ----------

	private String firstName(UUID userId) {
		return users.findById(userId).map(User::getFullName) // ⚠️ change if your field is called fullName / firstName
				.filter(n -> n != null && !n.isBlank()).map(n -> n.trim().split("\\s+")[0]).orElse("there");
	}

	// ---------- stat cards ----------

	private Stats stats(UUID userId, ZonedDateTime now) {
		Instant nowI = now.toInstant();

		// Scheduled: next 7 days
		long scheduled = posts.dashCountUpcoming(userId, nowI, nowI.plus(Duration.ofDays(7)));

		// Published: month-to-date vs the same stretch of last month
		ZonedDateTime monthStart = now.withDayOfMonth(1).toLocalDate().atStartOfDay(IST);
		Duration elapsed = Duration.between(monthStart, now);
		ZonedDateTime lastMonthStart = monthStart.minusMonths(1);

		long publishedNow = posts.dashCountPublished(userId, monthStart.toInstant(), nowI);
		long publishedPrev = posts.dashCountPublished(userId, lastMonthStart.toInstant(),
				lastMonthStart.plus(elapsed).toInstant());

		// Reach & engagement: last 30 days vs the 30 before
		Instant from30 = nowI.minus(Duration.ofDays(30));
		Instant from60 = nowI.minus(Duration.ofDays(60));

		long[] cur = reachAndInteractions(userId, from30, nowI);
		long[] prev = reachAndInteractions(userId, from60, from30);

		double engNow = pct(cur[1], cur[0]);
		Double reachChange = prev[0] > 0 ? round1((cur[0] - prev[0]) * 100.0 / prev[0]) : null;
		Double engChange = prev[0] > 0 ? round1(engNow - pct(prev[1], prev[0])) : null;

		return new Stats(scheduled, publishedNow, publishedNow - publishedPrev, cur[0], reachChange, round1(engNow),
				engChange);
	}

	private long[] reachAndInteractions(UUID userId, Instant from, Instant to) {
		List<Object[]> rows = metrics.sumReachAndInteractions(userId, from, to);
		if (rows.isEmpty() || rows.get(0) == null)
			return new long[] { 0, 0 };
		Object[] r = rows.get(0);
		return new long[] { num(r[0]), num(r[1]) };
	}

	// ---------- up next ----------

	private List<UpNextItem> upNext(UUID userId, Instant now) {
		return posts
				.dashUpNext(userId, now, PageRequest.of(0, 4)).stream().map(p -> new UpNextItem(p.getId(), titleOf(p),
						p.getScheduledAt(), new HashSet<>(p.getChannels()), p.getFormat(), p.getStatus().name()))
				.toList();
	}

	// ---------- what's working ----------

	private WhatsWorking whatsWorking(UUID userId, ZonedDateTime now) {
		// Bars: Monday → Sunday of the current week (IST)
		LocalDate monday = now.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
		Instant weekFrom = monday.atStartOfDay(IST).toInstant();
		Instant weekTo = monday.plusDays(7).atStartOfDay(IST).toInstant();

		Map<LocalDate, Long> byDay = new HashMap<>();
		for (Object[] r : metrics.dailyReach(userId, weekFrom, weekTo)) {
			byDay.put(toLocalDate(r[0]), num(r[1]));
		}

		List<DayReach> week = new ArrayList<>(7);
		for (int i = 0; i < 7; i++) {
			LocalDate d = monday.plusDays(i);
			week.add(new DayReach(d, DAY_LABELS[i], byDay.getOrDefault(d, 0L)));
		}

		// Top posts from the last 30 days
		Instant from = now.toInstant().minus(Duration.ofDays(30));

		PostMetric byReach = metrics.topByReach(userId, from, PageRequest.of(0, 1)).stream().findFirst().orElse(null);

		// Pick a different post for the engagement row if possible
		UUID reachPostId = byReach == null ? null : byReach.getPost().getId();
		PostMetric byEng = metrics.topByEngagement(userId, from, MIN_REACH_FOR_ENGAGEMENT, PageRequest.of(0, 2))
				.stream().filter(m -> !Objects.equals(m.getPost().getId(), reachPostId)).findFirst().orElse(null);

		return new WhatsWorking(week, toTop(byReach), toTop(byEng));
	}

	private TopPost toTop(PostMetric m) {
		if (m == null)
			return null;
		return new TopPost(m.getPost().getId(), titleOf(m.getPost()), m.getReach(), round1(m.getEngagementRate()));
	}

	// ---------- campaigns ----------

	private List<CampaignCard> activeCampaigns(UUID userId, LocalDate today) {
		List<Campaign> list = campaigns.dashActive(userId, today, PageRequest.of(0, 3));
		if (list.isEmpty())
			return List.of();

		// campaignId -> status -> count (one query for all campaigns)
		Map<UUID, Map<PostStatus, Long>> counts = new HashMap<>();
		for (Object[] r : posts.countByCampaignAndStatus(userId)) {
			counts.computeIfAbsent((UUID) r[0], k -> new EnumMap<>(PostStatus.class)).put((PostStatus) r[1], num(r[2]));
		}

		return list.stream().map(c -> {
			Map<PostStatus, Long> s = counts.getOrDefault(c.getId(), Map.of());
			long total = s.values().stream().mapToLong(Long::longValue).sum();
			long ready = s.getOrDefault(PostStatus.SCHEDULED, 0L) + s.getOrDefault(PostStatus.PUBLISHING, 0L)
					+ s.getOrDefault(PostStatus.PUBLISHED, 0L);

			return new CampaignCard(c.getId(), c.getName(), displayStatus(c, today), c.getStartsOn(), c.getEndsOn(),
					new HashSet<>(c.getChannels()), ready, total);
		}).toList();
	}

	private static String displayStatus(Campaign c, LocalDate today) {
		if (c.getStatus() == CampaignStatus.DRAFT)
			return "DRAFT";
		if (c.getStartsOn() != null && !today.isBefore(c.getStartsOn()))
			return "LIVE";
		return "SCHEDULED";
	}

	// ---------- helpers ----------

	private static String titleOf(Post p) {
		if (p.getTitle() != null && !p.getTitle().isBlank())
			return p.getTitle();
		String cap = p.getCaption();
		if (cap == null || cap.isBlank())
			return "Untitled post";
		String line = cap.strip().split("\\R", 2)[0];
		return line.length() > 70 ? line.substring(0, 69) + "…" : line;
	}

	private static LocalDate toLocalDate(Object o) {
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
}