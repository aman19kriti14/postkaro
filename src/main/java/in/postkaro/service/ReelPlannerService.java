package in.postkaro.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * The "director": looks at the user's photos and brief, and plans a reel shot
 * by shot — order, camera motion, AI motion, on-screen text, music mood and
 * grade. Rendering happens elsewhere; this only decides what the reel should
 * be.
 */
@Slf4j
@Service
public class ReelPlannerService {

	private static final int MAX_PHOTOS = 5;

	@Value("${openai.api.key:}")
	private String openaiApiKey;

	@Value("${postkaro.ai.reel-model:${postkaro.ai.caption-model:gpt-4o-mini}}")
	private String model;

	private final RestClient restClient = RestClient.create();
	private final ObjectMapper objectMapper = new ObjectMapper();

	/**
	 * One shot of the reel. Either a user photo (photoIndex) or an AI image
	 * (imagePrompt).
	 */
	/**
	 * One shot of the reel. Either a user photo (photoIndex) or an AI image
	 * (imagePrompt). treatment: "full" fills the frame; "card" floats the image
	 * over a blurred backdrop — for screenshots, posters, anything with text.
	 */
	public record Shot(String source, Integer photoIndex, String imagePrompt, String camera, String aiMotion,
			String text, double seconds, String treatment) {

		public boolean card() {
			return "card".equals(treatment);
		}
	}

	public record ReelPlan(String hook, List<Shot> shots, String musicMood, int bpm, String colorGrade,
			String caption) {
	}

	private static final String SYSTEM_PROMPT = """
			You are the creative director of India's best short-form video studio. A small business
			gives you a brief and up to 5 photos. You plan a 9:16 Instagram Reel that looks
			professionally edited — the kind people watch twice and share. Never a slideshow.

			HOW A GREAT REEL WORKS
			- Shot 1 is the hook: the most striking visual + bold text that makes people stop in
			  under 1 second. Never open on a logo or a plain product shot with no tension.
			- Tell a tiny story: hook → build (2-3 shots) → payoff shot → call to action.
			- 4 to 7 shots, 12-20 seconds total. Hook shot 1.5-2s, others 2-3.5s, final CTA 2.5-3s.
			- Every shot moves. Choose "camera" from: push_in, pull_out, pan_left, pan_right,
			  tilt_up, tilt_down, orbit, parallax, whip_in, static_zoom_punch.
			  Vary them — never the same move twice in a row.
			- "aiMotion" describes what comes alive inside the frame when animated: steam rising,
			  fabric swaying in breeze, light sweeping across, petals falling, liquid pouring,
			  city lights flickering. Subtle, physical, believable. Never change faces or text.
			- On-screen "text": max 6 words per shot, punchy, spoken language. Not every shot
			  needs text — silence on a beautiful shot is fine (use empty string).
			- The final shot carries the CTA text (e.g. "Order on WhatsApp", "Visit this weekend").

			USING THE USER'S PHOTOS
			- Look at every photo. Use ALL usable photos, in the order that tells the best story,
			  not the order uploaded. Refer to them by photoIndex (0-based).
			- NEVER use the same photo in two shots in a row. Reuse a photo at most once overall.
			- If a photo is blurry or irrelevant, skip it.
			- "treatment" for every user photo: "card" if it is an app screenshot, website, document,
			  poster, flyer, menu or anything with text or UI; "full" for real-world photos (people,
			  places, products). AI shots are always "full".
			- Card shots (screenshots/UI) are for explaining, not for wow: keep each to 1.5-2s and never
			  more than 2 card shots in a row. The hook (shot 1) must be a "full" shot.
			- If a photo is a finished design or poster (the result the product made), it is the PAYOFF:
			  place it right after the build-up, give it 2.5-3s, and put the payoff text on it.
			- If there are fewer photos than the story needs, add AI shots (source "ai") with a
			  detailed imagePrompt that MATCHES the look of the user's photos: same setting,
			  lighting, colours and style, so the reel feels shot by one person.

			REAL PEOPLE — HARD RULE
			- If the brief names or depicts a real, identifiable person (politician, celebrity,
			  public figure, or any specific individual), NEVER write an imagePrompt that shows
			  that person. Show them ONLY through the user's uploaded photos. AI shots may show
			  places, crowds from behind, objects, graphics, landscapes — never their face or body.
			- Never write text that puts invented words in a real person's mouth.

			FACTS
			- Never invent prices, discounts, dates, phone numbers, addresses or statistics.
			  Use only what the brief states.

			MUSIC & LOOK
			- musicMood: one short phrase an instrumental music generator can use, e.g.
			  "upbeat indian lo-fi with tabla, warm", "cinematic build with strings, inspiring",
			  "funky bollywood brass groove, festive". Match the business and the story.
			- bpm: 80-130, matching the mood. Cuts will land on beats.
			- colorGrade: one of warm_film, clean_bright, moody_cinematic, vibrant_pop, soft_pastel.

			CAPTION
			- A creator-style Instagram caption: strong first line, short lines, specific,
			  natural CTA, 3-5 niche hashtags. No clichés (elevate, unlock, game-changer, journey).

			Return ONLY valid JSON, no markdown:
			{"hook":"...","musicMood":"...","bpm":110,"colorGrade":"warm_film","caption":"...",
			 "shots":[{"source":"upload","photoIndex":0,"imagePrompt":"","camera":"push_in",
			           "aiMotion":"...","text":"...","seconds":2.0,"treatment":"full"}]}
			""";

	public ReelPlan plan(String brief, List<String> photoUrls, String language, String tone) {
		List<String> photos = photoUrls == null ? List.of()
				: photoUrls.stream().filter(u -> u != null && !u.isBlank()).limit(MAX_PHOTOS).toList();

		String userText = "Brief: " + brief + "\nTone: " + (tone == null ? "warm" : tone)
				+ "\nOn-screen text and caption language: " + (language == null ? "English" : language)
				+ "\nNumber of user photos: " + photos.size() + (photos.isEmpty() ? " (plan all shots as AI shots)"
						: " (attached below, in index order 0.." + (photos.size() - 1) + ")");

		// Vision message: text first, then each photo
		List<Map<String, Object>> content = new ArrayList<>();
		content.add(Map.of("type", "text", "text", userText));
		for (String url : photos) {
			content.add(Map.of("type", "image_url", "image_url", Map.of("url", url)));
		}

		String raw = call(content);
		return parse(raw, photos.size());
	}

	@SuppressWarnings("unchecked")
	private String call(List<Map<String, Object>> userContent) {
		Map<String, Object> body = new HashMap<>();
		body.put("model", model);
		body.put("messages", List.of(Map.of("role", "system", "content", SYSTEM_PROMPT),
				Map.of("role", "user", "content", userContent)));

		if (model.startsWith("gpt-4")) {
			body.put("max_tokens", 2500);
			body.put("temperature", 0.9);
		} else {
			body.put("max_completion_tokens", 6000);
			body.put("reasoning_effort", "low");
		}

		Map<String, Object> response = restClient.post().uri("https://api.openai.com/v1/chat/completions")
				.header("Authorization", "Bearer " + openaiApiKey).contentType(MediaType.APPLICATION_JSON).body(body)
				.retrieve().body(Map.class);

		List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
		Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
		return ((String) message.get("content")).trim();
	}

	private ReelPlan parse(String raw, int photoCount) {
		String cleaned = raw;
		int start = cleaned.indexOf('{');
		int end = cleaned.lastIndexOf('}');
		if (start >= 0 && end > start) {
			cleaned = cleaned.substring(start, end + 1);
		}

		try {
			JsonNode json = objectMapper.readTree(cleaned);
			List<Shot> shots = new ArrayList<>();
			Integer lastPhoto = null;

			for (JsonNode s : json.path("shots")) {
				String source = s.path("source").asText("ai");
				Integer photoIndex = s.hasNonNull("photoIndex") ? s.path("photoIndex").asInt() : null;
				String imagePrompt = s.path("imagePrompt").asText("");

				// Drop shots that point at a photo that doesn't exist, or AI shots with no
				// prompt
				if ("upload".equals(source) && (photoIndex == null || photoIndex < 0 || photoIndex >= photoCount)) {
					continue;
				}
				if ("ai".equals(source) && imagePrompt.isBlank()) {
					continue;
				}

				// Same photo twice in a row reads as a glitch — keep the first one
				if ("upload".equals(source) && photoIndex.equals(lastPhoto)) {
					continue;
				}
				lastPhoto = "upload".equals(source) ? photoIndex : null;

				String treatment = "upload".equals(source) && "card".equals(s.path("treatment").asText("")) ? "card"
						: "full";
				double seconds = Math.max(1.2, Math.min(4.0, s.path("seconds").asDouble(2.5)));
				shots.add(new Shot(source, "upload".equals(source) ? photoIndex : null, imagePrompt,
						s.path("camera").asText("push_in"), s.path("aiMotion").asText(""), s.path("text").asText(""),
						seconds, treatment));
			}

			if (shots.size() < 2) {
				throw new IllegalStateException("Plan had fewer than 2 usable shots");
			}

			int bpm = Math.max(70, Math.min(140, json.path("bpm").asInt(110)));
			return new ReelPlan(json.path("hook").asText(""), shots, json.path("musicMood").asText("upbeat, warm"), bpm,
					json.path("colorGrade").asText("warm_film"), json.path("caption").asText(""));
		} catch (Exception e) {
			log.warn("Reel plan parse failed: {}", raw);
			throw new IllegalStateException("Couldn't plan the reel. Please try again.", e);
		}
	}
}