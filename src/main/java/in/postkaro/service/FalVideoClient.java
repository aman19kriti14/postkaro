package in.postkaro.service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;

import lombok.extern.slf4j.Slf4j;

/**
 * Animates a still image into a short clip with an image-to-video model on fal
 * (Kling v3 Pro by default). Uses fal's queue API: submit, poll, fetch — video
 * jobs take 1–3 minutes, too long for a single blocking request.
 */
@Slf4j
@Component
public class FalVideoClient {

	private static final String QUEUE_URL = "https://queue.fal.run";
	private static final Duration POLL_EVERY = Duration.ofSeconds(3);
	private static final Duration GIVE_UP_AFTER = Duration.ofMinutes(6);

	/**
	 * Always added: keeps the model animating the image instead of reinventing it.
	 */
	private static final String MOTION_RULES = " Keep every face, product, logo, label and detail exactly as in the image."
			+ " Subtle, realistic, physical motion only. Smooth, slow, cinematic camera. No new objects, no text.";

	private static final String NEGATIVE = "blur, distortion, warped or morphing faces, extra fingers, melting objects,"
			+ " changing product shape, text, subtitles, captions, watermark, flicker, low quality";

	private final RestClient restClient;
	private final String model;

	public FalVideoClient(@Value("${FAL_API_KEY}") String apiKey,
			@Value("${postkaro.ai.video-model:fal-ai/kling-video/v3/pro/image-to-video}") String model) {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofSeconds(10));
		factory.setReadTimeout(Duration.ofSeconds(60));

		this.model = model;
		this.restClient = RestClient.builder().requestFactory(factory)
				.defaultHeader(HttpHeaders.AUTHORIZATION, "Key " + apiKey)
				.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).build();
	}

	/**
	 * @param imageUrl public URL of the still (Cloudinary or fal)
	 * @param motion   what should move, from the reel plan's aiMotion
	 * @param seconds  clip length; the renderer trims it to the beat afterwards
	 * @return URL of the generated clip (hosted by fal — download it soon)
	 */
	public String animate(String imageUrl, String motion, int seconds) {
		Map<String, Object> body = new HashMap<>();
		body.put("start_image_url", imageUrl);
		body.put("prompt",
				(motion == null || motion.isBlank() ? "Gentle natural movement in the scene." : motion) + MOTION_RULES);
		body.put("duration", String.valueOf(Math.max(3, Math.min(10, seconds))));
		body.put("negative_prompt", NEGATIVE);
		body.put("generate_audio", false); // the reel gets one music track over everything

		JsonNode submitted = restClient.post().uri(QUEUE_URL + "/" + model).body(body).retrieve()
				.onStatus(HttpStatusCode::isError, (req, res) -> {
					throw new FalImageClient.FalException("fal video submit " + res.getStatusCode().value() + ": "
							+ new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8));
				}).body(JsonNode.class);

		if (submitted == null || !submitted.hasNonNull("status_url") || !submitted.hasNonNull("response_url")) {
			throw new FalImageClient.FalException("fal video submit returned no request: " + submitted);
		}
		String statusUrl = submitted.get("status_url").asText();
		String responseUrl = submitted.get("response_url").asText();
		log.info("Animating shot, fal request {}", submitted.path("request_id").asText());

		long deadline = System.currentTimeMillis() + GIVE_UP_AFTER.toMillis();
		while (true) {
			if (System.currentTimeMillis() > deadline) {
				throw new FalImageClient.FalException(
						"fal video timed out after " + GIVE_UP_AFTER.toMinutes() + " min");
			}
			sleep();

			JsonNode status = restClient.get().uri(statusUrl).retrieve().body(JsonNode.class);
			String state = status == null ? "" : status.path("status").asText("");

			if ("COMPLETED".equals(state)) {
				break;
			}
			if (!"IN_QUEUE".equals(state) && !"IN_PROGRESS".equals(state)) {
				throw new FalImageClient.FalException("fal video failed: " + status);
			}
		}

		JsonNode result = restClient.get().uri(responseUrl).retrieve().onStatus(HttpStatusCode::isError, (req, res) -> {
			throw new FalImageClient.FalException("fal video result " + res.getStatusCode().value() + ": "
					+ new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8));
		}).body(JsonNode.class);

		String url = result == null ? "" : result.path("video").path("url").asText("");
		if (url.isBlank()) {
			throw new FalImageClient.FalException("fal video returned no clip: " + result);
		}
		return url;
	}

	private static void sleep() {
		try {
			Thread.sleep(POLL_EVERY.toMillis());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new FalImageClient.FalException("Interrupted while waiting for the video");
		}
	}
}