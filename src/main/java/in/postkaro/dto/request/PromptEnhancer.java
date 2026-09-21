package in.postkaro.dto.request;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Turns a one-line user idea ("promo post for my dental clinic") into a
 * detailed, art-directed image prompt — the kind of prompt that produces
 * agency-quality output from Nano Banana Pro. Then FalImageClient.generate()
 * takes it from there.
 *
 * Usage: EnhancedPrompt p = enhancer.enhance(request); List<String> images =
 * falImageClient.generate(p.prompt(), referenceImages, p.aspectRatio(), "2K",
 * 2);
 */
@Component
public class PromptEnhancer {

	private static final String SYSTEM_PROMPT = """
			You are the senior art director at a top Indian social media agency.
			Your job: turn a short idea from a business owner into ONE detailed image-generation
			prompt for a state-of-the-art image model that renders text well.

			Write the prompt like a design brief. Always cover, in this order:
			1. FORMAT: platform, aspect ratio, "high resolution, professional marketing design".
			2. EXACT ON-IMAGE TEXT: a headline of max 6 words and an optional subheadline of max
			   12 words, each written in double quotes exactly as they must appear. Optional CTA
			   button text of max 3 words. Nothing else. Less text is better.
			3. LAYOUT & HIERARCHY: where the main visual sits, where text sits, what the eye sees
			   first. Someone scrolling must understand the post in 2 seconds.
			4. MAIN VISUAL: one clear hero subject, described concretely (materials, setting,
			   lighting, camera angle).
			5. STYLE: colours (use the brand colours given), typography feel, mood.
			6. AVOID: clutter, tiny text, extra words, misspellings, random icons, watermarks,
			   distorted hands or faces, generic stock-photo look.

			Hard rules:
			- NEVER invent facts: no prices, discounts, dates, phone numbers, addresses, doctor
			  names or statistics unless the user provided them. If none given, write copy
			  that needs none (e.g. "Book your visit today").
			- Spell every word of on-image text carefully; repeat it exactly in quotes.
			- If hasLogoReference is true: "Place the provided logo image exactly as given,
			  small, in a corner. Do not redraw, restyle or re-spell the logo."
			  If false: "Leave clean empty space in the top-left corner for a logo. Do not
			  write the brand name as a logo."
			- If hasProductReference is true: "Keep the product from the reference image
			  exactly identical — same shape, colour, label and proportions. Do not redesign it."
			- Keep the setting and people culturally natural for India unless told otherwise.
			- If onImageLanguage is not English, write the on-image text in that language and
			  script, and keep it even shorter (max 4 words headline).
			- Match the content type:
			    product_promo  → hero product shot, premium commercial photography
			    offer          → bold offer headline, clear urgency, strong CTA
			    event          → event name, date/venue only if given, energetic visual
			    infographic    → clean UI cards / steps with arrows, max 4 steps, 1-3 words each
			    festive        → festival mood, warm decor, tasteful, brand still prominent
			    announcement   → one clear message, minimal, confident
			    educational    → one tip or fact, large readable text, simple supporting visual
			    testimonial    → short quote in quotes, customer context, trust-building

			Respond ONLY with JSON: {"prompt": "...", "aspectRatio": "1:1|4:5|9:16|16:9", "caption": "..."}
			"caption" is a short Instagram caption (max 2 sentences + max 5 relevant hashtags),
			in the brand's tone, plain and human. Never use clichés like "fear not",
			"game-changer", "sprinkle", "unlock", "elevate", "level up", "look no further".
			""";

	private final RestClient restClient;
	private final ObjectMapper objectMapper;
	private final String model;

	public PromptEnhancer(@Value("${OPENAI_API_KEY}") String apiKey,
			@Value("${postkaro.ai.prompt-model:gpt-4o-mini}") String model, ObjectMapper objectMapper) {
		this.model = model;
		this.objectMapper = objectMapper;
		this.restClient = RestClient.builder().baseUrl("https://api.openai.com/v1")
				.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
				.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).build();
	}

	public EnhancedPrompt enhance(EnhanceRequest request) {
		String userMessage;
		try {
			userMessage = objectMapper.writeValueAsString(request);
		} catch (Exception e) {
			throw new IllegalArgumentException("Could not serialise enhance request", e);
		}

		Map<String, Object> body = Map.of("model", model, "temperature", 0.7, "response_format",
				Map.of("type", "json_object"), "messages", List.of(Map.of("role", "system", "content", SYSTEM_PROMPT),
						Map.of("role", "user", "content", userMessage)));

		JsonNode response = restClient.post().uri("/chat/completions").body(body).retrieve()
				.onStatus(HttpStatusCode::isError, (req, res) -> {
					String error = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
					throw new IllegalStateException("OpenAI " + res.getStatusCode().value() + ": " + error);
				}).body(JsonNode.class);

		try {
			String content = response.get("choices").get(0).get("message").get("content").asText();
			JsonNode json = objectMapper.readTree(content);
			String aspect = request.aspectRatio() != null && !request.aspectRatio().isBlank() ? request.aspectRatio() // user's
																														// explicit
																														// choice
																														// wins
					: json.path("aspectRatio").asText("1:1");
			return new EnhancedPrompt(json.get("prompt").asText(), aspect, json.path("caption").asText(""));
		} catch (Exception e) {
			throw new IllegalStateException("Prompt enhancer returned invalid JSON", e);
		}
	}

	/**
	 * What the frontend sends. Only idea is required; everything else improves
	 * quality.
	 *
	 * @param idea                the user's one-liner, e.g. "promo post for my
	 *                            dental clinic"
	 * @param contentType         product_promo | offer | event | infographic |
	 *                            festive | announcement | educational | testimonial
	 * @param brandName           e.g. "Bright Smiles Dental"
	 * @param brandColors         e.g. "#C8102E red, black, white"
	 * @param brandTone           e.g. "warm and trustworthy", "bold and playful"
	 * @param industry            e.g. "dental clinic", "D2C skincare", "cafe"
	 * @param onImageLanguage     "English", "Hindi", "Hinglish", "Tamil", ...
	 * @param platform            "Instagram feed", "Instagram story", "LinkedIn",
	 *                            ...
	 * @param aspectRatio         optional explicit ratio; null lets the enhancer
	 *                            choose
	 * @param hasLogoReference    true if the brand logo will be passed as a
	 *                            reference image
	 * @param hasProductReference true if a product photo will be passed as a
	 *                            reference image
	 */
	public record EnhanceRequest(String idea, String contentType, String brandName, String brandColors,
			String brandTone, String industry, String onImageLanguage, String platform, String aspectRatio,
			boolean hasLogoReference, boolean hasProductReference) {
	}

	public record EnhancedPrompt(String prompt, String aspectRatio, String caption) {
	}
}