package in.postkaro.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import in.postkaro.dto.request.AiStudioDtos.GenerateRequest;
import in.postkaro.dto.request.AiStudioDtos.IdeaSetView;
import in.postkaro.dto.request.AiStudioDtos.IdeaView;
import in.postkaro.entity.Idea;
import in.postkaro.entity.IdeaSet;
import in.postkaro.entity.PostMetric;
import in.postkaro.entity.User;
import in.postkaro.repository.IdeaSetRepository;
import in.postkaro.repository.PostMetricRepository;
import in.postkaro.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiStudioService {

	private static final Set<String> FORMATS = Set.of("reel", "carousel", "post", "story");
	private static final Set<String> CHANNELS = Set.of("instagram", "facebook", "linkedin", "youtube", "x");
	private static final Set<String> SOURCES = Set.of("brand_voice", "product_list", "past_top_posts",
			"uploaded_photos");
	private static final int IDEA_COUNT = 6;
	private final BrandSettingsService brandSettings;

	private final IdeaSetRepository sets;
	private final UserRepository users;
	private final PostMetricRepository metrics;
	private final InsightService insights;

	private final RestClient http = RestClient.create();
	private final ObjectMapper json = new ObjectMapper();

	@Value("${OPENAI_API_KEY}")
	private String openAiKey;

	// ---------- generate ----------

	/**
	 * Not @Transactional: the OpenAI call can take 10s+ and shouldn't hold a DB
	 * connection.
	 */
	public IdeaSetView generate(UUID userId, GenerateRequest req) {
		Set<String> formats = clean(req.formats(), FORMATS);
		Set<String> channels = clean(req.channels(), CHANNELS);
		Set<String> sources = req.sources() == null ? Set.of() : clean(req.sources(), SOURCES);
		if (formats.isEmpty())
			throw bad("Pick at least one format");
		if (channels.isEmpty())
			throw bad("Pick at least one channel");

		User user = users.findById(userId).orElseThrow(() -> bad("User not found"));
		Map<String, InsightService.Fact> facts = insights.factsFor(userId);

		String prompt = buildPrompt(user, req.brief().trim(), sources, formats, channels, facts, userId);
		JsonNode result = callOpenAi(prompt);

		IdeaSet set = IdeaSet.builder().user(users.getReferenceById(userId))
				.name(limit(text(result, "setName", "New ideas"), 80)).brief(req.brief().trim()).sources(sources)
				.formats(formats).channels(channels).build();

		int pos = 0;
		for (JsonNode n : result.path("ideas")) {
			if (pos >= IDEA_COUNT)
				break;

			String format = text(n, "format", "").toLowerCase();
			if (!formats.contains(format))
				continue; // AI ignored the constraint — drop it

			Set<String> ideaChannels = new LinkedHashSet<>();
			n.path("channels").forEach(c -> {
				String ch = c.asText("").toLowerCase();
				if (channels.contains(ch))
					ideaChannels.add(ch);
			});
			if (ideaChannels.isEmpty())
				ideaChannels.add(channels.iterator().next());

			String title = text(n, "title", "");
			if (title.isBlank())
				continue;

			set.getIdeas()
					.add(Idea.builder().ideaSet(set).position(pos++).format(format).channels(ideaChannels)
							.title(limit(title, 120)).description(limit(text(n, "description", ""), 400))
							.caption(text(n, "caption", null))
							.insight(pickInsight(facts, text(n, "insightKey", null), format)).build());
		}

		if (set.getIdeas().isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Couldn't generate ideas. Try again.");
		}

		IdeaSet saved = sets.save(set);
		return toView(saved);
	}

	// ---------- read ----------

	@Transactional(readOnly = true)
	public IdeaSetView get(UUID userId, UUID setId) {
		return sets.findFull(setId, userId).map(this::toView)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Idea set not found"));
	}

	@Transactional(readOnly = true)
	public IdeaSetView latest(UUID userId) {
		return sets.latestIds(userId, PageRequest.of(0, 1)).stream().findFirst()
				.flatMap(id -> sets.findFull(id, userId)).map(this::toView).orElse(null);
	}

	// ---------- prompt ----------

	private String buildPrompt(User user, String brief, Set<String> sources, Set<String> formats, Set<String> channels,
			Map<String, InsightService.Fact> facts, UUID userId) {

		StringBuilder ctx = new StringBuilder();
		if (sources.contains("brand_voice")) {
			var p = user.getProfile();
			if (p != null) {
				ctx.append("Brand: ").append(safe(p.getBrandName())).append('\n');
				ctx.append("Category: ").append(safe(p.getCategory())).append('\n');
				ctx.append("About: ").append(safe(p.getDescription())).append('\n');
			}
			String voice = brandSettings.promptContext(userId);
			if (!voice.isBlank()) {
				ctx.append("\nBrand voice (follow this strictly):\n").append(voice).append('\n');
			}
		}

		if (sources.contains("past_top_posts")) {
			List<PostMetric> top = metrics.topByReach(userId, Instant.now().minus(Duration.ofDays(90)),
					PageRequest.of(0, 5));
			if (!top.isEmpty()) {
				ctx.append("\nTheir best-performing past captions (match this voice):\n");
				for (PostMetric m : top) {
					String cap = m.getPost().getCaption();
					if (cap != null && !cap.isBlank())
						ctx.append("- ").append(limit(cap.strip(), 200)).append('\n');
				}
			}
		}
		// product_list and uploaded_photos: accepted but not wired yet

		String factList = facts.isEmpty() ? "(none — always use null)"
				: facts.values().stream().map(f -> "- " + f.key() + ": " + f.text()).collect(Collectors.joining("\n"));

		return """
				You write social media post ideas for a small Indian brand.

				%s

				Brief from the owner: "%s"

				Rules:
				- Return exactly %d ideas.
				- format must be one of: %s
				- channels must be a non-empty subset of: %s
				- Mix formats across the ideas where possible.
				- title: short and specific, max 8 words, no emojis, no hashtags.
				- description: 1–2 plain sentences describing what the post shows. Calm, concrete, no hype.
								- caption: a ready-to-use caption that follows the brand voice above exactly, max 60 words, up to 3 hashtags at the end.
				- insightKey: pick the key of ONE fact below that genuinely supports this idea, or null.
				  Never invent numbers. Never write statistics anywhere else.
				- setName: 2–4 word label for this batch, e.g. "This week · rain".

				Available facts:
				%s

				Respond with JSON only:
				{"setName": "...", "ideas": [{"format": "...", "channels": ["..."], "title": "...",
				  "description": "...", "caption": "...", "insightKey": null}]}
				"""
				.formatted(ctx.toString().isBlank() ? "(no brand context provided)" : ctx.toString().trim(),
						brief.replace("\"", "'"), IDEA_COUNT, String.join(", ", formats), String.join(", ", channels),
						factList);
	}

	private JsonNode callOpenAi(String prompt) {
		Map<String, Object> body = Map.of("model", "gpt-4o-mini", "temperature", 0.8, "response_format",
				Map.of("type", "json_object"), "messages", List.of(Map.of("role", "user", "content", prompt)));
		try {
			JsonNode res = http.post().uri("https://api.openai.com/v1/chat/completions")
					.header("Authorization", "Bearer " + openAiKey).contentType(MediaType.APPLICATION_JSON).body(body)
					.retrieve().body(JsonNode.class);
			String content = res.path("choices").path(0).path("message").path("content").asText("{}");
			return json.readTree(content);
		} catch (Exception e) {
			log.warn("OpenAI idea generation failed: {}", e.getMessage());
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Couldn't generate ideas. Try again.");
		}
	}

	// ---------- mapping ----------

	private String pickInsight(Map<String, InsightService.Fact> facts, String key, String format) {
		if (key != null && facts.containsKey(key))
			return facts.get(key).text();
		InsightService.Fact f = insights.fallbackFor(facts, format);
		return f == null ? null : f.text();
	}

	IdeaSetView toView(IdeaSet s) {
		List<IdeaView> ideas = new ArrayList<>();
		long kept = 0, dismissed = 0;
		for (Idea i : s.getIdeas()) {
			if (i.getStatus() == Idea.Status.DISMISSED)
				dismissed++;
			else
				kept++;
			ideas.add(new IdeaView(i.getId(), i.getFormat(), new LinkedHashSet<>(i.getChannels()), i.getTitle(),
					i.getDescription(), i.getInsight(), i.getStatus().name(), i.getDraftPostId(), i.getVisualUrl()));
		}
		return new IdeaSetView(s.getId(), s.getName(), s.getBrief(), new LinkedHashSet<>(s.getSources()),
				new LinkedHashSet<>(s.getFormats()), new LinkedHashSet<>(s.getChannels()), s.isSaved(), kept, dismissed,
				ideas, s.getCreatedAt());
	}

	// ---------- helpers ----------

	private static Set<String> clean(Set<String> in, Set<String> allowed) {
		Set<String> out = new LinkedHashSet<>();
		if (in != null)
			for (String v : in) {
				String x = v == null ? "" : v.trim().toLowerCase();
				if (allowed.contains(x))
					out.add(x);
			}
		return out;
	}

	private static String text(JsonNode n, String field, String fallback) {
		JsonNode v = n.path(field);
		return v.isMissingNode() || v.isNull() ? fallback : v.asText(fallback).trim();
	}

	private static String limit(String s, int max) {
		if (s == null)
			return null;
		return s.length() <= max ? s : s.substring(0, max - 1) + "…";
	}

	private static String safe(Object o) {
		if (o == null)
			return "";
		if (o instanceof Enum<?> e) {
			// FOOD_AND_BEVERAGE -> "food and beverage"
			return e.name().replace('_', ' ').toLowerCase();
		}
		return o.toString();
	}

	private static ResponseStatusException bad(String msg) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
	}
}