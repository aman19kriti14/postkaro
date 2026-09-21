package in.postkaro.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import in.postkaro.repository.BrandSettingsRepository;
import in.postkaro.repository.UserProfileRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * One idea → a matching N-slide carousel.
 *
 * 1. An LLM plans the carousel: one shared style guide + what each slide says.
 * 2. Slide 1 (the cover) is generated first. 3. Slides 2..N are generated in
 * parallel, each with slide 1 passed as a style reference so the whole set
 * shares colours, fonts and layout. 4. Everything is copied to Cloudinary.
 */
@Slf4j
@Component
public class CarouselGenerator {

	private static final String PLANNER_PROMPT = """
			You are the senior art director at a top Indian social media agency.
			Plan an Instagram carousel from a business owner's idea.

			Output ONLY JSON:
			{
			  "styleGuide": "one paragraph describing the SHARED design of every slide: background, colour palette (use the brand colours given), typography feel (headline font style, body font style), layout grid, where text sits, decorative elements, overall mood. Specific enough that a designer could make every slide look like one set.",
			  "slides": [
			    { "role": "cover|content|cta", "headline": "...", "body": "...", "visual": "..." }
			  ],
			  "caption": "..."
			}

			Rules for slides:
			- Exactly the number of slides requested.
			- Slide 1 is the cover: a bold hook headline (max 7 words), body empty or max 8 words.
			- Middle slides deliver the value: one idea per slide. Headline max 6 words, body max 20 words.
			- Last slide is a call to action (follow, save, visit, DM, book) — never invent links, phone numbers or prices.
			- "visual" = the one simple supporting visual for that slide (an object, icon-style illustration, photo subject). Keep it simple.
			- NEVER invent facts: no prices, discounts, dates, statistics, phone numbers or addresses unless the user gave them.
			- If onImageLanguage is not English, write headline and body in that language and script, and keep them shorter.
			- Spell everything carefully.

			Caption: max 3 short sentences + max 5 relevant hashtags, plain and human, in the brand's tone.
			Never use clichés like "fear not", "game-changer", "unlock", "elevate", "level up", "look no further".
			""";

	private final RestClient openai;
	private final ObjectMapper objectMapper;
	private final String model;
	private final FalImageClient fal;
	private final MediaService mediaService;
	private final BrandSettingsRepository brandRepo;
	private final UserProfileRepository profileRepo;

	public CarouselGenerator(@Value("${openai.api.key}") String openaiKey,
			@Value("${postkaro.ai.prompt-model:gpt-4o-mini}") String model, ObjectMapper objectMapper,
			FalImageClient fal, MediaService mediaService, BrandSettingsRepository brandRepo,
			UserProfileRepository profileRepo) {
		this.model = model;
		this.objectMapper = objectMapper;
		this.fal = fal;
		this.mediaService = mediaService;
		this.brandRepo = brandRepo;
		this.profileRepo = profileRepo;
		this.openai = RestClient.builder().baseUrl("https://api.openai.com/v1")
				.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + openaiKey)
				.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).build();
	}

	public record CarouselRequest(String idea, int slideCount, String aspectRatio, String language,
			List<String> productImageUrls, Boolean useLogo) {
	}

	public record SlidePlan(String role, String headline, String body, String visual) {
	}

	public record CarouselResult(List<String> urls, List<SlidePlan> slides, String caption, String aspectRatio) {
	}

	public CarouselResult generate(UUID userId, CarouselRequest req) {
		if (req.idea() == null || req.idea().isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tell us what the carousel is about");
		}
		int count = Math.max(2, Math.min(10, req.slideCount()));
		String aspect = List.of("1:1", "4:5").contains(req.aspectRatio()) ? req.aspectRatio() : "4:5";
		String language = req.language() == null || req.language().isBlank() ? "English" : req.language();

		// ---- brand kit ----
		var brand = brandRepo.findByUserId(userId).orElse(null);
		var profile = profileRepo.findByUserId(userId).orElse(null);
		String brandName = profile != null ? profile.getBrandName() : null;
		String industry = profile != null && profile.getCategory() != null
				? profile.getCategory().name().toLowerCase().replace('_', ' ')
				: null;
		String colors = brand != null && !brand.getColors().isEmpty() ? String.join(", ", brand.getColors()) : null;
		String tone = brand != null ? brand.getTone() : null;
		String headingFont = brand != null ? brand.getHeadingFont() : null;
		String bodyFont = brand != null ? brand.getBodyFont() : null;

		List<String> baseRefs = new ArrayList<>();
		if (req.productImageUrls() != null) {
			req.productImageUrls().stream().filter(u -> u != null && !u.isBlank()).limit(2).forEach(baseRefs::add);
		}
		boolean hasProduct = !baseRefs.isEmpty();
		boolean hasLogo = false;
		if (!Boolean.FALSE.equals(req.useLogo()) && brand != null && brand.getLogoUrl() != null
				&& !brand.getLogoUrl().isBlank()) {
			baseRefs.add(brand.getLogoUrl());
			hasLogo = true;
		}

		// ---- 1. plan ----
		JsonNode plan = plan(Map.of("idea", req.idea(), "slideCount", count, "onImageLanguage", language, "brandName",
				nz(brandName), "industry", nz(industry), "brandColors", nz(colors), "brandTone", nz(tone),
				"headingFont", nz(headingFont), "bodyFont", nz(bodyFont)));

		String styleGuide = plan.path("styleGuide").asText("");
		List<SlidePlan> slides = new ArrayList<>();
		for (JsonNode s : plan.path("slides")) {
			slides.add(new SlidePlan(s.path("role").asText("content"), s.path("headline").asText(""),
					s.path("body").asText(""), s.path("visual").asText("")));
		}
		if (slides.size() < 2) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Couldn't plan the carousel. Try again.");
		}
		if (slides.size() > count) {
			slides = slides.subList(0, count);
		}
		String caption = plan.path("caption").asText("");

		// ---- 2. cover first ----
		String coverPrompt = slidePrompt(slides.get(0), 1, slides.size(), styleGuide, aspect, hasLogo, hasProduct,
				false);
		String coverFalUrl = fal.generate(coverPrompt, baseRefs, aspect, "2K", 1).get(0);

		// ---- 3. remaining slides in parallel, cover as style reference ----
		List<String> styledRefs = new ArrayList<>();
		styledRefs.add(coverFalUrl); // first reference = the style to copy
		styledRefs.addAll(baseRefs);

		List<String> falUrls = new ArrayList<>();
		falUrls.add(coverFalUrl);
		try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Future<String>> futures = new ArrayList<>();
			for (int i = 1; i < slides.size(); i++) {
				String p = slidePrompt(slides.get(i), i + 1, slides.size(), styleGuide, aspect, hasLogo, hasProduct,
						true);
				futures.add(pool.submit(() -> fal.generate(p, styledRefs, aspect, "2K", 1).get(0)));
			}
			for (Future<String> f : futures) {
				falUrls.add(f.get());
			}
		} catch (Exception e) {
			log.warn("Carousel slide generation failed: {}", e.getMessage());
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Some slides couldn't be made. Try again.");
		}

		// ---- 4. store permanently ----
		List<String> urls = falUrls.stream().map(mediaService::storeFromUrl).toList();
		log.info("Carousel for user {}: {} slides", userId, urls.size());
		return new CarouselResult(urls, slides, caption, aspect);
	}

	// ---------- helpers ----------

	private String slidePrompt(SlidePlan s, int n, int total, String styleGuide, String aspect, boolean hasLogo,
			boolean hasProduct, boolean matchReference) {
		StringBuilder p = new StringBuilder();
		p.append("Slide ").append(n).append(" of ").append(total).append(" of an Instagram carousel. Aspect ratio ")
				.append(aspect).append(", high resolution, professional marketing design.\n\n");

		if (matchReference) {
			p.append("MATCH THE FIRST REFERENCE IMAGE EXACTLY in design: same background, same colour palette, ")
					.append("same fonts and text styling, same layout grid, margins and decorative elements. ")
					.append("It must look like the next page of the same set. Only the text and the supporting visual change. ")
					.append("Do not copy the first image's text.\n\n");
		}

		p.append("Shared style for the whole carousel: ").append(styleGuide).append("\n\n");

		p.append("On-image text — render EXACTLY this, perfectly spelled, nothing else:\n");
		p.append("Headline: \"").append(s.headline()).append("\"\n");
		if (s.body() != null && !s.body().isBlank()) {
			p.append("Body: \"").append(s.body()).append("\"\n");
		}
		p.append("Small slide number \"").append(n).append("/").append(total).append("\" in a corner.\n\n");

		p.append("Supporting visual: ").append(s.visual()).append(".\n");
		if ("cover".equals(s.role())) {
			p.append("This is the cover: the headline is large and dominant, designed to stop the scroll.\n");
		}
		if ("cta".equals(s.role())) {
			p.append("This is the final call-to-action slide: clear, confident, simple.\n");
		}
		if (hasProduct) {
			p.append(
					"Keep the product from the product reference image exactly identical — same shape, colour, label and proportions.\n");
		}
		if (hasLogo) {
			p.append(
					"Place the provided logo image exactly as given, small, in a corner. Do not redraw or re-spell it.\n");
		} else {
			p.append("Do not add any logo or brand wordmark.\n");
		}
		p.append(
				"\nAvoid: extra words, tiny text, misspellings, clutter, random icons, watermarks, distorted hands or faces.");
		return p.toString();
	}

	private JsonNode plan(Map<String, Object> input) {
		String userMessage;
		try {
			userMessage = objectMapper.writeValueAsString(input);
		} catch (Exception e) {
			throw new IllegalArgumentException(e);
		}
		Map<String, Object> body = Map.of("model", model, "temperature", 0.7, "response_format",
				Map.of("type", "json_object"), "messages", List.of(Map.of("role", "system", "content", PLANNER_PROMPT),
						Map.of("role", "user", "content", userMessage)));

		JsonNode res = openai.post().uri("/chat/completions").body(body).retrieve()
				.onStatus(HttpStatusCode::isError, (rq, rs) -> {
					String err = new String(rs.getBody().readAllBytes(), StandardCharsets.UTF_8);
					throw new IllegalStateException("OpenAI " + rs.getStatusCode().value() + ": " + err);
				}).body(JsonNode.class);
		try {
			return objectMapper.readTree(res.path("choices").path(0).path("message").path("content").asText());
		} catch (Exception e) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Couldn't plan the carousel. Try again.");
		}
	}

	private static String nz(String s) {
		return s == null ? "" : s;
	}
}