package in.postkaro.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import in.postkaro.entity.Campaign;
import in.postkaro.entity.Post;
import in.postkaro.entity.PostMedia;
import in.postkaro.entity.User;
import in.postkaro.enums.PostStatus;
import in.postkaro.repository.CampaignRepository;
import in.postkaro.repository.PostRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CampaignService {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
	private static final Set<String> FORMATS = Set.of("reel", "carousel", "post", "story");
	private static final Set<String> STAGES = Set.of("tease", "explain", "proof", "convert");

	private final CampaignRepository campaigns;
	private final PostRepository posts;

	@Transactional
	@SuppressWarnings("unchecked")
	public Campaign create(User user, Map<String, Object> b) {
		String name = str(b, "name");
		if (name.isBlank())
			throw bad("Give the campaign a name");
		if (name.length() > 120)
			throw bad("Keep the name under 120 characters");

		List<String> channels = b.get("channels") instanceof List<?> l ? (List<String>) l : List.of();
		if (channels.isEmpty())
			throw bad("Pick at least one channel");

		LocalDate startsOn = date(b, "startsOn");
		LocalDate endsOn = date(b, "endsOn");
		if (endsOn.isBefore(startsOn))
			throw bad("The end date is before the start date");

		List<Map<String, Object>> items = b.get("items") instanceof List<?> l ? (List<Map<String, Object>>) l
				: List.of();
		if (items.isEmpty())
			throw bad("Approve at least one post before saving the campaign");

		Campaign campaign = campaigns.save(Campaign.builder().user(user).name(name).brief(str(b, "brief"))
				.offer(str(b, "offer")).goal(str(b, "goal")).cadence(str(b, "cadence")).tone(str(b, "tone"))
				.visuals(str(b, "visuals")).look(str(b, "look")).channels(new HashSet<>(channels)).startsOn(startsOn)
				.endsOn(endsOn).build());

		for (Map<String, Object> item : items) {
			String title = str(item, "title");
			String hook = str(item, "hook");
			if (title.isBlank())
				continue;

			String format = str(item, "format");
			String stage = str(item, "stage");

			List<String> itemChannels = item.get("channels") instanceof List<?> l ? (List<String>) l : channels;

			String text = hook.isBlank() ? title : title + "\n\n" + hook;

			posts.save(Post.builder().user(user).campaign(campaign).caption(text).prompt(text).tone(campaign.getTone())
					.format(FORMATS.contains(format) ? format : "post").stage(STAGES.contains(stage) ? stage : null)
					.channels(new HashSet<>(itemChannels)).scheduledAt(plannedAt(item)).status(PostStatus.DRAFT)
					.build());
		}

		return campaign;
	}

	// Planned slot in IST; null if the item has no valid date
	private java.time.Instant plannedAt(Map<String, Object> item) {
		try {
			LocalDate d = LocalDate.parse(str(item, "date"));
			LocalTime t = str(item, "time").isBlank() ? LocalTime.of(11, 30) : LocalTime.parse(str(item, "time"));
			return d.atTime(t).atZone(IST).toInstant();
		} catch (Exception e) {
			return null;
		}
	}

	private static ResponseStatusException bad(String msg) {
		return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, msg);
	}

	private static String str(Map<String, Object> b, String k) {
		Object v = b.get(k);
		return v == null ? "" : v.toString().trim();
	}

	private static LocalDate date(Map<String, Object> b, String k) {
		try {
			return LocalDate.parse(str(b, k));
		} catch (Exception e) {
			throw bad("Pick a valid " + (k.equals("startsOn") ? "start" : "end") + " date");
		}
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> list(UUID userId) {
		Map<UUID, List<Post>> byCampaign = posts.findByUserIdAndCampaignIsNotNull(userId).stream()
				.collect(Collectors.groupingBy(p -> p.getCampaign().getId()));

		List<Map<String, Object>> out = new ArrayList<>();
		for (Campaign c : campaigns.findByUserIdOrderByStartsOnDesc(userId)) {
			Map<String, Object> m = summary(c);
			m.put("counts", counts(byCampaign.getOrDefault(c.getId(), List.of())));
			out.add(m);
		}
		return out;
	}

	@Transactional(readOnly = true)
	public Map<String, Object> detail(UUID campaignId, UUID userId) {
		Campaign c = campaigns.findByIdAndUserId(campaignId, userId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Campaign not found"));

		List<Post> list = posts.findByCampaignIdAndUserIdOrderByScheduledAtAsc(campaignId, userId);

		Map<String, Object> m = summary(c);
		m.put("brief", c.getBrief());
		m.put("offer", c.getOffer());
		m.put("goal", c.getGoal());
		m.put("cadence", c.getCadence());
		m.put("tone", c.getTone());
		m.put("visuals", c.getVisuals());
		m.put("look", c.getLook());
		m.put("counts", counts(list));
		m.put("posts", list.stream().map(this::postView).toList());
		return m;
	}

	private Map<String, Object> summary(Campaign c) {
		LocalDate today = LocalDate.now(IST);
		String status = today.isBefore(c.getStartsOn()) ? "upcoming" : today.isAfter(c.getEndsOn()) ? "ended" : "live";

		Map<String, Object> m = new HashMap<>();
		m.put("id", c.getId().toString());
		m.put("name", c.getName());
		m.put("status", status);
		m.put("startsOn", c.getStartsOn().toString());
		m.put("endsOn", c.getEndsOn().toString());
		m.put("channels", c.getChannels());
		return m;
	}

	private Map<String, Integer> counts(List<Post> list) {
		int needsWork = 0, ready = 0, scheduled = 0, published = 0;
		for (Post p : list) {
			switch (p.getStatus()) {
			case DRAFT -> {
				if (isReady(p))
					ready++;
				else
					needsWork++;
			}
			case SCHEDULED -> scheduled++;
			case PUBLISHED -> published++;
			default -> {
			}
			}
		}
		Map<String, Integer> m = new HashMap<>();
		m.put("total", list.size());
		m.put("needsWork", needsWork);
		m.put("ready", ready);
		m.put("scheduled", scheduled);
		m.put("published", published);
		return m;
	}

	private boolean isReady(Post p) {
		return p.getMedia() != null && !p.getMedia().isEmpty() && p.getChannels() != null && !p.getChannels().isEmpty()
				&& p.getCaption() != null && !p.getCaption().isBlank();
	}

	private Map<String, Object> postView(Post p) {
		String caption = p.getCaption() == null ? "" : p.getCaption().strip();
		String[] parts = caption.split("\\R", 2);
		PostMedia first = p.getMedia().isEmpty() ? null : p.getMedia().get(0);

		Map<String, Object> m = new HashMap<>();
		m.put("id", p.getId().toString());
		m.put("title", parts[0].isBlank() ? "Untitled post" : parts[0]);
		m.put("hook", parts.length > 1 ? parts[1].strip() : "");
		m.put("status", p.getStatus().name());
		m.put("ready", p.getStatus() == PostStatus.DRAFT && isReady(p));
		m.put("format", p.getFormat());
		m.put("stage", p.getStage());
		m.put("channels", p.getChannels());
		m.put("scheduledAt", p.getScheduledAt());
		m.put("publishedAt", p.getPublishedAt());
		m.put("mediaUrl", first != null ? first.getUrl() : null);
		m.put("mediaType", first != null ? first.getType() : null);
		return m;
	}
}