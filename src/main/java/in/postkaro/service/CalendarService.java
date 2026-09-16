package in.postkaro.service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import in.postkaro.dto.response.CalendarResponse;
import in.postkaro.dto.response.CalendarResponse.CadenceGap;
import in.postkaro.dto.response.CalendarResponse.CalendarPost;
import in.postkaro.dto.response.CalendarResponse.CampaignBand;
import in.postkaro.dto.response.CalendarResponse.Summary;
import in.postkaro.entity.Campaign;
import in.postkaro.entity.Post;
import in.postkaro.enums.PostStatus;
import in.postkaro.repository.CampaignRepository;
import in.postkaro.repository.PostRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CalendarService {

	public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	// Statuses that count as "something is going out" for cadence gaps
	private static final Set<PostStatus> ACTIVE = EnumSet.of(PostStatus.NEEDS_REVIEW, PostStatus.SCHEDULED,
			PostStatus.PUBLISHING, PostStatus.PUBLISHED);

	private final PostRepository postRepository;
	private final CampaignRepository campaignRepository;

	@Transactional(readOnly = true)
	public CalendarResponse getMonth(UUID userId, YearMonth month, String channel, int minGapDays) {
		LocalDate monthStart = month.atDay(1);
		LocalDate monthEnd = month.atEndOfMonth();

		LocalDate gridFrom = monthStart.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
		LocalDate gridTo = monthEnd.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));

		Instant from = gridFrom.atStartOfDay(IST).toInstant();
		Instant to = gridTo.plusDays(1).atStartOfDay(IST).toInstant();

		String filter = (channel == null || channel.isBlank() || channel.equalsIgnoreCase("all")) ? null : channel;

		List<CalendarPost> chips = new ArrayList<>();
		for (Post post : postRepository.findForCalendar(userId, from, to)) {
			post.getChannels().stream().sorted().filter(ch -> filter == null || ch.equalsIgnoreCase(filter))
					.forEach(ch -> chips.add(toChip(post, ch)));
		}
		chips.sort(Comparator.comparing(CalendarPost::date));

		List<CalendarPost> monthChips = chips.stream()
				.filter(c -> !c.date().toLocalDate().isBefore(monthStart) && !c.date().toLocalDate().isAfter(monthEnd))
				.toList();

		List<CadenceGap> gaps = findGaps(monthChips, monthStart, monthEnd, minGapDays, filter);

		Summary summary = new Summary(monthChips.size(), count(monthChips, PostStatus.PUBLISHED),
				count(monthChips, PostStatus.SCHEDULED) + count(monthChips, PostStatus.PUBLISHING),
				count(monthChips, PostStatus.NEEDS_REVIEW), count(monthChips, PostStatus.DRAFT),
				count(monthChips, PostStatus.FAILED), gaps.size());

		List<CampaignBand> campaigns = campaignRepository.findOverlapping(userId, gridFrom, gridTo).stream()
				.filter(c -> filter == null || c.getChannels().isEmpty()
						|| c.getChannels().stream().anyMatch(ch -> ch.equalsIgnoreCase(filter)))
				.map(this::toBand).toList();

		return new CalendarResponse(gridFrom, gridTo, summary, chips, campaigns, gaps);
	}

	// ---------- actions ----------

	@Transactional
	public void reschedule(UUID userId, UUID postId, LocalDate date, LocalTime time) {
		Post post = findOwned(userId, postId);

		if (post.getStatus() == PostStatus.PUBLISHED || post.getStatus() == PostStatus.PUBLISHING) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Published posts can't be moved");
		}

		// Dragging to a new day keeps the original time; default 10:00 if none
		LocalTime finalTime = time != null ? time
				: post.getScheduledAt() != null ? post.getScheduledAt().atZone(IST).toLocalTime() : LocalTime.of(10, 0);

		Instant newTime = date.atTime(finalTime).atZone(IST).toInstant();
		if (newTime.isBefore(Instant.now())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Can't schedule in the past");
		}

		post.setScheduledAt(newTime);
		if (post.getStatus() == PostStatus.FAILED) {
			post.setStatus(PostStatus.SCHEDULED); // moving a failed post retries it
		}
	}

	@Transactional
	public void approve(UUID userId, UUID postId) {
		Post post = findOwned(userId, postId);

		if (post.getStatus() != PostStatus.NEEDS_REVIEW) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Post isn't waiting for review");
		}
		if (post.getScheduledAt() == null || post.getScheduledAt().isBefore(Instant.now())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Scheduled time has passed — reschedule before approving");
		}
		post.setStatus(PostStatus.SCHEDULED);
	}

	private Post findOwned(UUID userId, UUID postId) {
		return postRepository.findByIdAndUserId(postId, userId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found"));
	}

	// ---------- mapping ----------

	private CalendarPost toChip(Post post, String channel) {
		Instant when = post.getStatus() == PostStatus.PUBLISHED && post.getPublishedAt() != null ? post.getPublishedAt()
				: post.getScheduledAt();

		boolean draggable = post.getStatus() != PostStatus.PUBLISHED && post.getStatus() != PostStatus.PUBLISHING;

		String title = post.getTitle() != null && !post.getTitle().isBlank() ? truncate(post.getTitle(), 60)
				: titleFrom(post.getCaption());

		return new CalendarPost(post.getId(), title, truncate(post.getCaption(), 140), thumbnailOf(post),
				post.getFormat(), post.getStage(), channel.toUpperCase(), post.getStatus().name(),
				OffsetDateTime.ofInstant(when, IST), post.getCampaign() != null ? post.getCampaign().getId() : null,
				draggable);
	}

	private CampaignBand toBand(Campaign c) {
		return new CampaignBand(c.getId(), c.getName(), c.getStartsOn(), c.getEndsOn());
	}

	private String thumbnailOf(Post post) {
		if (post.getMedia() == null || post.getMedia().isEmpty())
			return null;
		return post.getMedia().get(0).getUrl();
	}

	private String titleFrom(String caption) {
		if (caption == null || caption.isBlank())
			return "Untitled post";
		return truncate(caption.strip().split("\\R", 2)[0], 60);
	}

	private String truncate(String s, int max) {
		if (s == null)
			return null;
		String t = s.strip();
		return t.length() <= max ? t : t.substring(0, max - 1).stripTrailing() + "…";
	}

	private int count(List<CalendarPost> chips, PostStatus status) {
		return (int) chips.stream().filter(c -> c.status().equals(status.name())).count();
	}

	// ---------- cadence gaps ----------

	private List<CadenceGap> findGaps(List<CalendarPost> chips, LocalDate start, LocalDate end, int minGapDays,
			String channel) {
		Set<LocalDate> activeDays = chips.stream().filter(c -> ACTIVE.contains(PostStatus.valueOf(c.status())))
				.map(c -> c.date().toLocalDate()).collect(Collectors.toSet());

		List<CadenceGap> gaps = new ArrayList<>();
		LocalDate runStart = null;

		for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
			if (!activeDays.contains(d)) {
				if (runStart == null)
					runStart = d;
			} else if (runStart != null) {
				addGap(gaps, runStart, d.minusDays(1), minGapDays, channel);
				runStart = null;
			}
		}
		if (runStart != null)
			addGap(gaps, runStart, end, minGapDays, channel);

		return gaps;
	}

	private void addGap(List<CadenceGap> gaps, LocalDate from, LocalDate to, int min, String channel) {
		int days = (int) ChronoUnit.DAYS.between(from, to) + 1;
		if (days >= min) {
			gaps.add(new CadenceGap(from, to, days, channel == null ? null : channel.toUpperCase()));
		}
	}
}