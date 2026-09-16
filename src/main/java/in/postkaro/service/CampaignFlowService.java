package in.postkaro.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import in.postkaro.dto.response.CampaignFlowResponse;
import in.postkaro.dto.response.CampaignFlowResponse.Check;
import in.postkaro.dto.response.CampaignFlowResponse.Checks;
import in.postkaro.dto.response.CampaignFlowResponse.FlowPost;
import in.postkaro.entity.Campaign;
import in.postkaro.entity.Post;
import in.postkaro.entity.PostMedia;
import in.postkaro.entity.User;
import in.postkaro.enums.CampaignStatus;
import in.postkaro.enums.PostStatus;
import in.postkaro.repository.CampaignRepository;
import in.postkaro.repository.PostRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CampaignFlowService {

	private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

	private final CampaignRepository campaignRepository;
	private final PostRepository postRepository;
	private final CampaignPlanService planService;

	// ---------- lifecycle ----------

	@Transactional
	public CampaignFlowResponse createDraft(User user) {
		Campaign c = campaignRepository.save(Campaign.builder().user(user).build());
		return toResponse(c, List.of());
	}

	@Transactional(readOnly = true)
	public CampaignFlowResponse get(UUID userId, UUID id) {
		Campaign c = owned(userId, id);
		return toResponse(c, postRepository.findByCampaignIdOrderByScheduledAtAsc(id));
	}

	// Autosave: only keys present in the body are changed
	@Transactional
	public CampaignFlowResponse saveBrief(UUID userId, UUID id, Map<String, Object> body) {
		Campaign c = editable(userId, id);

		if (body.containsKey("name")) {
			String name = text(body.get("name"));
			c.setName(name.isBlank() ? "Untitled campaign" : limit(name, 120));
		}
		if (body.containsKey("brief"))
			c.setBrief(text(body.get("brief")));
		if (body.containsKey("offer"))
			c.setOffer(limit(text(body.get("offer")), 300));
		if (body.containsKey("goal"))
			c.setGoal(limit(text(body.get("goal")), 30));
		if (body.containsKey("cadence"))
			c.setCadence(limit(text(body.get("cadence")), 20));
		if (body.containsKey("tone"))
			c.setTone(limit(text(body.get("tone")), 30));
		if (body.containsKey("visuals"))
			c.setVisuals(limit(text(body.get("visuals")), 30));
		if (body.containsKey("look"))
			c.setLook(limit(text(body.get("look")), 30));
		if (body.containsKey("autoPublish"))
			c.setAutoPublish(Boolean.TRUE.equals(body.get("autoPublish")));
		if (body.containsKey("startsOn"))
			c.setStartsOn(dateOrNull(body.get("startsOn")));
		if (body.containsKey("endsOn"))
			c.setEndsOn(dateOrNull(body.get("endsOn")));
		if (body.get("channels") instanceof List<?> list) {
			Set<String> channels = new HashSet<>();
			list.forEach(ch -> channels.add(ch.toString().toLowerCase()));
			c.getChannels().clear();
			c.getChannels().addAll(channels);
		}
		return get(userId, id);
	}

	@Transactional
	public void saveStep(UUID userId, UUID id, int step) {
		Campaign c = editable(userId, id);
		c.setCurrentStep(Math.max(1, Math.min(4, step)));
	}

	// ---------- step 2: plan ----------

	@Transactional
	public CampaignFlowResponse generatePlan(UUID userId, UUID id) {
		Campaign c = editable(userId, id);

		Map<String, Object> brief = new HashMap<>();
		brief.put("name", c.getName());
		brief.put("brief", nz(c.getBrief()));
		brief.put("offer", nz(c.getOffer()));
		brief.put("goal", c.getGoal());
		brief.put("cadence", c.getCadence());
		brief.put("tone", c.getTone());
		brief.put("visuals", c.getVisuals());
		brief.put("channels", new ArrayList<>(c.getChannels()));
		brief.put("startsOn", c.getStartsOn() == null ? "" : c.getStartsOn().toString());
		brief.put("endsOn", c.getEndsOn() == null ? "" : c.getEndsOn().toString());

		List<Map<String, Object>> plan = planService.buildPlan(brief); // validates, throws 422 with a message

		// Regenerating replaces the old plan
		postRepository.deleteByCampaignId(id);
		postRepository.flush();

		for (Map<String, Object> item : plan) {
			LocalDate date = LocalDate.parse(item.get("date").toString());
			LocalTime time = LocalTime.parse(item.get("time").toString());

			Post post = Post.builder().user(c.getUser()).campaign(c).title(limit(text(item.get("title")), 120))
					.caption(text(item.get("hook"))).tone(c.getTone()).format(text(item.get("format")))
					.stage(text(item.get("stage"))).status(PostStatus.DRAFT)
					.scheduledAt(date.atTime(time).atZone(CalendarService.IST).toInstant()).build();
			post.getChannels().addAll(c.getChannels());
			postRepository.save(post);
		}

		c.setCurrentStep(2);
		return get(userId, id);
	}

	// Edit / approve / slot / visual on one post; only keys present are changed
	@Transactional
	public FlowPost updatePost(UUID userId, UUID campaignId, UUID postId, Map<String, Object> body) {
		editable(userId, campaignId);
		Post post = postRepository.findByIdAndUserId(postId, userId)
				.filter(p -> p.getCampaign() != null && p.getCampaign().getId().equals(campaignId))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found"));

		if (body.containsKey("title"))
			post.setTitle(limit(text(body.get("title")), 120));
		if (body.containsKey("caption"))
			post.setCaption(text(body.get("caption")));
		if (body.containsKey("format"))
			post.setFormat(limit(text(body.get("format")), 20));
		if (body.containsKey("approved"))
			post.setApproved(Boolean.TRUE.equals(body.get("approved")));

		if (body.get("channels") instanceof List<?> list) {
			post.getChannels().clear();
			list.forEach(ch -> post.getChannels().add(ch.toString().toLowerCase()));
		}

		if (body.containsKey("date") || body.containsKey("time")) {
			ZonedDateTime current = post.getScheduledAt() != null ? post.getScheduledAt().atZone(CalendarService.IST)
					: ZonedDateTime.now(CalendarService.IST).plusDays(1).withHour(11).withMinute(30);
			LocalDate date = body.containsKey("date") ? LocalDate.parse(text(body.get("date"))) : current.toLocalDate();
			LocalTime time = body.containsKey("time") ? LocalTime.parse(text(body.get("time"))) : current.toLocalTime();
			post.setScheduledAt(date.atTime(time).atZone(CalendarService.IST).toInstant());
		}

		if (body.containsKey("visualUrl")) {
			String url = text(body.get("visualUrl"));
			post.getMedia().clear();
			if (!url.isBlank()) {
				post.getMedia().add(PostMedia.builder().post(post).url(url).build());
			}
		}
		return toFlowPost(post);
	}

	@Transactional
	public CampaignFlowResponse approveAll(UUID userId, UUID id) {
		editable(userId, id);
		postRepository.findByCampaignIdOrderByScheduledAtAsc(id).forEach(p -> p.setApproved(true));
		return get(userId, id);
	}

	// ---------- step 3: checks ----------

	@Transactional(readOnly = true)
	public Checks checks(UUID userId, UUID id, Set<String> connectedPlatforms) {
		Campaign c = owned(userId, id);
		List<Post> posts = postRepository.findByCampaignIdOrderByScheduledAtAsc(id);
		List<Post> approved = posts.stream().filter(Post::isApproved).toList();
		int total = posts.size();

		long withVisual = approved.stream().filter(p -> !p.getMedia().isEmpty()).count();

		Set<String> missing = new HashSet<>();
		approved.forEach(p -> p.getChannels().forEach(ch -> {
			if (!connectedPlatforms.contains(ch.toLowerCase()))
				missing.add(ch);
		}));

		Instant now = Instant.now();
		boolean inPast = approved.stream()
				.anyMatch(p -> p.getScheduledAt() == null || p.getScheduledAt().isBefore(now));

		boolean clash = approved.stream().anyMatch(p -> p.getScheduledAt() != null && postRepository.existsClash(userId,
				id, p.getScheduledAt().minusSeconds(3600), p.getScheduledAt().plusSeconds(3600)));

		int channelCount = c.getChannels().size();

		List<Check> items = List.of(
				new Check("approved", !approved.isEmpty(), approved.size() + " of " + total + " posts approved",
						"Unapproved posts stay as drafts and will not publish."),
				new Check("visuals", true, // informational, never blocks
						withVisual + " of " + approved.size() + " visuals ready",
						"Posts without a visual publish as text only."),
				new Check("channels", missing.isEmpty(),
						missing.isEmpty() ? channelCount + " channels connected"
								: "Reconnect " + String.join(", ", missing),
						missing.isEmpty() ? "Tokens valid, no reconnection needed."
								: "These channels need to be connected before publishing."),
				new Check("slots", !inPast, inPast ? "Some slots are in the past" : "All slots are in the future",
						inPast ? "Move them to a later time." : "Every approved post has a time ahead."),
				new Check("clashes", !clash, clash ? "Clashes on the calendar" : "No clashes on the calendar",
						clash ? "Another post is scheduled within an hour of one of these slots."
								: "Nothing else is scheduled within an hour of these slots."));

		// Clashes warn but don't block
		boolean canPublish = items.stream().filter(i -> !i.key().equals("clashes")).allMatch(Check::ok);

		return new Checks(items, canPublish);
	}

	// ---------- step 4: publish ----------

	@Transactional
	public CampaignFlowResponse publish(UUID userId, UUID id, Set<String> connectedPlatforms) {
		Campaign c = editable(userId, id);

		Checks checks = checks(userId, id, connectedPlatforms);
		if (!checks.canPublish()) {
			String reason = checks.items().stream().filter(i -> !i.ok() && !i.key().equals("clashes")).map(Check::label)
					.findFirst().orElse("Checks didn't pass");
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
		}

		postRepository.findByCampaignIdOrderByScheduledAtAsc(id).stream().filter(Post::isApproved)
				.forEach(p -> p.setStatus(PostStatus.SCHEDULED));

		c.setStatus(CampaignStatus.SCHEDULED);
		c.setCurrentStep(4);
		return get(userId, id);
	}

	// ---------- helpers ----------

	private Campaign owned(UUID userId, UUID id) {
		Campaign c = campaignRepository.findById(id)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Campaign not found"));
		if (!c.getUser().getId().equals(userId)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Campaign not found");
		}
		return c;
	}

	private Campaign editable(UUID userId, UUID id) {
		Campaign c = owned(userId, id);
		if (c.getStatus() != CampaignStatus.DRAFT) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "This campaign is already published");
		}
		return c;
	}

	private CampaignFlowResponse toResponse(Campaign c, List<Post> posts) {
		return new CampaignFlowResponse(c.getId(), c.getName(), c.getBrief(), c.getOffer(), c.getGoal(), c.getCadence(),
				c.getTone(), c.getVisuals(), c.getLook(), c.getChannels().stream().sorted().toList(), c.getStartsOn(),
				c.getEndsOn(), c.getStatus().name(), c.getCurrentStep(), c.isAutoPublish(),
				posts.stream().map(this::toFlowPost).toList());
	}

	private FlowPost toFlowPost(Post p) {
		ZonedDateTime at = p.getScheduledAt() == null ? null : p.getScheduledAt().atZone(CalendarService.IST);
		return new FlowPost(p.getId(), p.getTitle(), p.getCaption(), p.getFormat(), p.getStage(),
				p.getChannels().stream().sorted().toList(), at == null ? null : at.toLocalDate(),
				at == null ? null : at.format(HHMM), p.isApproved(),
				p.getMedia().isEmpty() ? null : p.getMedia().get(0).getUrl(), p.getStatus().name());
	}

	private static String text(Object o) {
		return o == null ? "" : o.toString().trim();
	}

	private static String nz(String s) {
		return s == null ? "" : s;
	}

	private static String limit(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max);
	}

	private static LocalDate dateOrNull(Object o) {
		String s = text(o);
		if (s.isBlank())
			return null;
		try {
			return LocalDate.parse(s);
		} catch (Exception e) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid date: " + s);
		}
	}
}