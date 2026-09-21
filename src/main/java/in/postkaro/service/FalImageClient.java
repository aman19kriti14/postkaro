package in.postkaro.service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Thin client for fal.ai image generation using Nano Banana Pro (Gemini 3 Pro
 * Image).
 *
 * Two modes, picked automatically by generate(): - TEXT-TO-IMAGE: no reference
 * images → create the visual purely from the prompt (infographics, promo
 * posters, event announcements, quote posts). - EDIT / REFERENCE: one or more
 * reference images → keep the user's real product, logo or photo and build the
 * new visual around it (product launches, brand posts).
 */
@Component
public class FalImageClient {

	private static final String BASE_URL = "https://fal.run";
	private static final String TEXT_TO_IMAGE_MODEL = "fal-ai/nano-banana-pro";
	private static final String EDIT_MODEL = "fal-ai/nano-banana-pro/edit";

	private final RestClient restClient;

	public FalImageClient(@Value("${FAL_API_KEY}") String apiKey) {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofSeconds(10));
		// 2K generations can take 30–90s; don't let the default timeout kill them
		factory.setReadTimeout(Duration.ofSeconds(180));

		this.restClient = RestClient.builder().baseUrl(BASE_URL).requestFactory(factory)
				.defaultHeader(HttpHeaders.AUTHORIZATION, "Key " + apiKey)
				.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).build();
	}

	/**
	 * Main entry point. Reference images are OPTIONAL.
	 *
	 * @param prompt          what to create
	 * @param referenceImages product photos / logo / brand assets as public URLs or
	 *                        base64 data URIs. Pass null or an empty list for pure
	 *                        text-to-image.
	 * @param aspectRatio     "1:1" feed, "4:5" portrait feed, "9:16" story/reel
	 * @param resolution      "1K" for previews, "2K" for anything that gets posted
	 * @param numImages       variations to return (1–4)
	 * @return URLs of the generated images (hosted by fal; copy them to your own
	 *         storage)
	 */
	public List<String> generate(String prompt, List<String> referenceImages, String aspectRatio, String resolution,
			int numImages) {
		Map<String, Object> body = new HashMap<>();
		body.put("prompt", prompt);
		body.put("aspect_ratio", aspectRatio);
		body.put("resolution", resolution);
		body.put("num_images", numImages);
		body.put("output_format", "png");
		body.put("limit_generations", true);

		boolean hasReferences = referenceImages != null && !referenceImages.isEmpty();
		String model = hasReferences ? EDIT_MODEL : TEXT_TO_IMAGE_MODEL;
		if (hasReferences) {
			body.put("image_urls", referenceImages);
		}

		return call(model, body, prompt);
	}

	private List<String> call(String model, Map<String, Object> body, String prompt) {
		JsonNode response = restClient.post().uri("/" + model).body(body).retrieve()
				.onStatus(HttpStatusCode::isError, (req, res) -> {
					String error = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
					throw new FalException("fal.ai " + res.getStatusCode().value() + ": " + error);
				}).body(JsonNode.class);

		List<String> urls = new ArrayList<>();
		if (response != null && response.has("images")) {
			for (JsonNode image : response.get("images")) {
				urls.add(image.get("url").asText());
			}
		}
		if (urls.isEmpty()) {
			throw new FalException("fal.ai returned no images for prompt: " + prompt);
		}
		return urls;
	}

	public static class FalException extends RuntimeException {
		public FalException(String message) {
			super(message);
		}
	}
}