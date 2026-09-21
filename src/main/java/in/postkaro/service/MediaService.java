package in.postkaro.service;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import in.postkaro.dto.request.PromptEnhancer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class MediaService {

	@Value("${fal.api.key:}")
	private String falApiKey;

	@Value("${openai.api.key:}")
	private String openaiApiKey;

	@Value("${cloudinary.cloud-name:}")
	private String cloudName;

	@Value("${cloudinary.api-key:}")
	private String cloudApiKey;

	@Value("${cloudinary.api-secret:}")
	private String cloudApiSecret;

	private final RestClient restClient = RestClient.create();

	// ---------- NEW: Nano Banana Pro pipeline ----------
	private final PromptEnhancer promptEnhancer;
	private final FalImageClient falImageClient;
	private final in.postkaro.repository.BrandSettingsRepository brandRepo;
	private final in.postkaro.repository.UserProfileRepository profileRepo;

	/**
	 * What /posts/generate-image accepts. Only prompt is required; old clients
	 * sending {prompt, size} keep working.
	 */
	public record ImageRequest(String prompt, String size, String aspectRatio, String contentType, String language,
			List<String> productImageUrls, Boolean useLogo, Integer variations) {
	}

	/**
	 * Idea → art-directed prompt (with the user's brand kit) → Nano Banana Pro →
	 * Cloudinary. Uses edit mode automatically when a product photo or logo is
	 * passed; pure text-to-image otherwise.
	 */
	public Map<String, Object> generateImage(java.util.UUID userId, ImageRequest req) {
		if (req.prompt() == null || req.prompt().isBlank()) {
			throw new IllegalArgumentException("prompt is required");
		}

		// ---- brand kit (all optional) ----
		var brand = userId == null ? null : brandRepo.findByUserId(userId).orElse(null);
		var profile = userId == null ? null : profileRepo.findByUserId(userId).orElse(null);

		String brandName = profile != null ? profile.getBrandName() : null;
		String industry = profile != null && profile.getCategory() != null
				? profile.getCategory().name().toLowerCase().replace('_', ' ')
				: null;
		if (profile != null && profile.getDescription() != null && !profile.getDescription().isBlank()) {
			industry = (industry == null ? "" : industry + " — ") + profile.getDescription();
		}
		String colors = brand != null && !brand.getColors().isEmpty() ? String.join(", ", brand.getColors()) : null;
		String tone = brand != null ? brand.getTone() : null;

		// ---- reference images: product photos first, then the logo ----
		List<String> refs = new java.util.ArrayList<>();
		if (req.productImageUrls() != null) {
			req.productImageUrls().stream().filter(u -> u != null && !u.isBlank()).limit(3).forEach(refs::add);
		}
		boolean hasProduct = !refs.isEmpty();
		boolean hasLogo = false;
		if (!Boolean.FALSE.equals(req.useLogo()) && brand != null && brand.getLogoUrl() != null
				&& !brand.getLogoUrl().isBlank()) {
			refs.add(brand.getLogoUrl());
			hasLogo = true;
		}

		String aspect = req.aspectRatio();
		if (aspect == null || aspect.isBlank()) {
			aspect = switch (req.size() == null ? "" : req.size()) {
			case "portrait", "story", "reel" -> "9:16";
			case "feed", "portrait_feed" -> "4:5";
			case "landscape" -> "16:9";
			case "square" -> "1:1";
			default -> null; // let the enhancer decide
			};
		}

		// ---- 1. enhance ----
		var enhanced = promptEnhancer.enhance(new PromptEnhancer.EnhanceRequest(req.prompt(),
				req.contentType() == null ? "announcement" : req.contentType(), brandName, colors, tone, industry,
				req.language() == null ? "English" : req.language(), "Instagram", aspect, hasLogo, hasProduct));
		log.info("Enhanced image prompt ({} refs): {}", refs.size(), enhanced.prompt());

		// ---- 2. generate ----
		int n = req.variations() == null ? 1 : Math.max(1, Math.min(4, req.variations()));
		String finalAspect = List.of("1:1", "4:5", "9:16", "16:9", "3:4", "4:3").contains(enhanced.aspectRatio())
				? enhanced.aspectRatio()
				: "1:1";
		List<String> falUrls = falImageClient.generate(enhanced.prompt(), refs, finalAspect, "2K", n);

		// ---- 3. store permanently ----
		List<String> urls = falUrls.stream().map(this::uploadToCloudinary).toList();

		Map<String, Object> out = new java.util.HashMap<>();
		out.put("url", urls.get(0)); // backwards compatible with the old response
		out.put("urls", urls);
		out.put("aspectRatio", finalAspect);
		out.put("caption", enhanced.caption());
		out.put("prompt", enhanced.prompt());
		return out;
	}

	/** Old signature — kept so existing callers compile. No brand kit applied. */
	public Map<String, Object> generateImage(String userPrompt, String size) {
		return generateImage(null, new ImageRequest(userPrompt, size, null, null, null, null, false, 1));
	}

	/** Previous FLUX Pro 1.1 path, kept for one release as a fallback. Unused. */
	@SuppressWarnings("unchecked")
	@Deprecated
	public Map<String, Object> generateImageFluxLegacy(String userPrompt, String size) {
		String imagePrompt = craftImagePrompt(userPrompt);
		System.out.println("IMAGE PROMPT: " + imagePrompt);

		String imageSize = "square_hd";
		if ("landscape".equals(size))
			imageSize = "landscape_16_9";
		if ("portrait".equals(size))
			imageSize = "portrait_16_9";

		Map<String, Object> body = Map.of("prompt", imagePrompt, "image_size", imageSize, "num_images", 1,
				"num_inference_steps", 28, "guidance_scale", 3.5, "enable_safety_checker", true);

		Map<String, Object> response = restClient.post().uri("https://fal.run/fal-ai/flux-pro/v1.1")
				.header("Authorization", "Key " + falApiKey).contentType(MediaType.APPLICATION_JSON).body(body)
				.retrieve().body(Map.class);

		List<Map<String, Object>> images = (List<Map<String, Object>>) response.get("images");
		if (images != null && !images.isEmpty()) {
			String falUrl = (String) images.get(0).get("url");

			// Upload to Cloudinary for permanent URL
			String permanentUrl = uploadToCloudinary(falUrl);

			return Map.of("url", permanentUrl, "width", images.get(0).getOrDefault("width", 1024), "height",
					images.get(0).getOrDefault("height", 1024), "prompt", imagePrompt);
		}
		throw new RuntimeException("No image generated");
	}

	@SuppressWarnings("unchecked")
	public Map<String, Object> generateVideo(String userPrompt) {
		String videoPrompt = craftVideoPrompt(userPrompt);
		System.out.println("VIDEO PROMPT: " + videoPrompt);

		try {
			Map<String, Object> response = restClient.post()
					.uri("https://fal.run/fal-ai/kling-video/v1/standard/text-to-video")
					.header("Authorization", "Key " + falApiKey).contentType(MediaType.APPLICATION_JSON)
					.body(Map.of("prompt", videoPrompt, "duration", "5", "aspect_ratio", "1:1")).retrieve()
					.body(Map.class);

			if (response != null && response.containsKey("video")) {
				Map<String, Object> video = (Map<String, Object>) response.get("video");
				String falUrl = (String) video.get("url");
				String permanentUrl = uploadToCloudinary(falUrl);
				return Map.of("url", permanentUrl, "type", "video", "prompt", videoPrompt);
			}
		} catch (Exception e) {
			System.out.println("Video gen failed: " + e.getMessage());
		}

		throw new RuntimeException("Video generation failed.");
	}

	@SuppressWarnings("unchecked")
	private String uploadToCloudinary(String sourceUrl) {
		String uploadUrl = "https://api.cloudinary.com/v1_1/" + cloudName + "/auto/upload";

		// Use unsigned upload with fetch URL
		Map<String, Object> uploadBody = Map.of("file", sourceUrl, "upload_preset", "postkaro_unsigned");

		// First try unsigned. If that fails, use signed.
		try {
			Map<String, Object> result = restClient.post().uri(uploadUrl).contentType(MediaType.APPLICATION_JSON)
					.body(uploadBody).retrieve().body(Map.class);

			String url = (String) result.get("secure_url");
			System.out.println("CLOUDINARY URL: " + url);
			return url;
		} catch (Exception e) {
			System.out.println("Unsigned upload failed, trying signed: " + e.getMessage());
		}

		// Signed upload fallback
		long timestamp = System.currentTimeMillis() / 1000;
		String toSign = "timestamp=" + timestamp + cloudApiSecret;
		String signature;
		try {
			java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
			byte[] digest = md.digest(toSign.getBytes());
			StringBuilder sb = new StringBuilder();
			for (byte b : digest)
				sb.append(String.format("%02x", b));
			signature = sb.toString();
		} catch (Exception e) {
			throw new RuntimeException("Failed to sign upload", e);
		}

		Map<String, Object> signedBody = Map.of("file", sourceUrl, "api_key", cloudApiKey, "timestamp", timestamp,
				"signature", signature);

		Map<String, Object> result = restClient.post().uri(uploadUrl).contentType(MediaType.APPLICATION_JSON)
				.body(signedBody).retrieve().body(Map.class);

		String url = (String) result.get("secure_url");
		System.out.println("CLOUDINARY URL (signed): " + url);
		return url;
	}

	@SuppressWarnings("unchecked")
	private String craftImagePrompt(String userBrief) {
		Map<String, Object> body = Map.of("model", "gpt-4o-mini", "messages",
				List.of(Map.of("role", "system", "content",
						"""
								You are an expert at writing prompts for AI image generation.
								Given a social media post brief, create a detailed image prompt that would make a stunning, professional-quality social media image.
								Rules:
								- Describe the visual scene in detail: composition, lighting, colors, style
								- Make it photorealistic or high-quality illustration style
								- Avoid text in images (AI is bad at rendering text)
								- Focus on the mood and feeling of the brand
								- Keep it under 200 words
								- Output ONLY the image prompt, nothing else
								"""),
						Map.of("role", "user", "content", "Brief: " + userBrief)),
				"max_tokens", 300, "temperature", 0.8);

		Map<String, Object> response = restClient.post().uri("https://api.openai.com/v1/chat/completions")
				.header("Authorization", "Bearer " + openaiApiKey).contentType(MediaType.APPLICATION_JSON).body(body)
				.retrieve().body(Map.class);

		List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
		Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
		return (String) message.get("content");
	}

	@SuppressWarnings("unchecked")
	private String craftVideoPrompt(String userBrief) {
		Map<String, Object> body = Map.of("model", "gpt-4o-mini", "messages",
				List.of(Map.of("role", "system", "content", """
						You are an expert at writing prompts for AI video generation.
						Given a social media post brief, create a short video prompt.
						Rules:
						- Describe a simple, visually appealing 3-5 second scene
						- Focus on one subject with gentle motion
						- Keep it cinematic and professional
						- Under 100 words
						- Output ONLY the video prompt, nothing else
						"""), Map.of("role", "user", "content", "Brief: " + userBrief)), "max_tokens", 150,
				"temperature", 0.7);

		Map<String, Object> response = restClient.post().uri("https://api.openai.com/v1/chat/completions")
				.header("Authorization", "Bearer " + openaiApiKey).contentType(MediaType.APPLICATION_JSON).body(body)
				.retrieve().body(Map.class);

		List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
		Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
		return (String) message.get("content");
	}
	// ---------- NEW: storage helpers (existing methods above are untouched)
	// ----------

	/**
	 * Upload raw bytes (logo, user upload, poster) to Cloudinary; returns a
	 * permanent URL.
	 */
	/**
	 * Upload raw bytes (logo, user upload, poster) to Cloudinary; returns a
	 * permanent URL.
	 */
	public String storeBytes(byte[] bytes, String contentType) {
		String dataUri = "data:" + contentType + ";base64," + java.util.Base64.getEncoder().encodeToString(bytes);
		String resourceType = contentType.startsWith("video/") ? "video" : "image";
		return uploadAs(dataUri, resourceType);
	}

	/**
	 * Same as uploadToCloudinary, but forces image/video so Cloudinary never stores
	 * it as "raw".
	 */
	@SuppressWarnings("unchecked")
	private String uploadAs(String file, String resourceType) {
		String uploadUrl = "https://api.cloudinary.com/v1_1/" + cloudName + "/" + resourceType + "/upload";

		try {
			Map<String, Object> result = restClient.post().uri(uploadUrl).contentType(MediaType.APPLICATION_JSON)
					.body(Map.of("file", file, "upload_preset", "postkaro_unsigned")).retrieve().body(Map.class);
			return (String) result.get("secure_url");
		} catch (Exception e) {
			System.out.println("Unsigned " + resourceType + " upload failed, trying signed: " + e.getMessage());
		}

		long timestamp = System.currentTimeMillis() / 1000;
		String signature;
		try {
			java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
			StringBuilder sb = new StringBuilder();
			for (byte b : md.digest(
					("timestamp=" + timestamp + cloudApiSecret).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
				sb.append(String.format("%02x", b));
			signature = sb.toString();
		} catch (Exception e) {
			throw new RuntimeException("Failed to sign upload", e);
		}

		Map<String, Object> result = restClient.post().uri(uploadUrl).contentType(MediaType.APPLICATION_JSON)
				.body(Map.of("file", file, "api_key", cloudApiKey, "timestamp", timestamp, "signature", signature))
				.retrieve().body(Map.class);
		return (String) result.get("secure_url");
	}

	/**
	 * Copy a remote file (e.g. a fal.ai link) to Cloudinary; returns a permanent
	 * URL.
	 */
	public String storeFromUrl(String sourceUrl) {
		return uploadToCloudinary(sourceUrl);
	}
}