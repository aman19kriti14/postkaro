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
 * Generates an original instrumental track for a reel (Google Lyria 2 on fal by
 * default: 30 s, 48 kHz, cleared for commercial use under fal's terms).
 * Original music means no copyright strikes and no muted reels.
 */
@Slf4j
@Component
public class FalMusicClient {

	private static final String QUEUE_URL = "https://queue.fal.run";
	private static final Duration POLL_EVERY = Duration.ofSeconds(2);
	private static final Duration GIVE_UP_AFTER = Duration.ofMinutes(3);

	private static final String NEGATIVE = "vocals, singing, lyrics, speech, voice, slow intro, long fade in, silence,"
			+ " low quality, distorted, muddy";

	private final RestClient restClient;
	private final String model;

	public FalMusicClient(@Value("${FAL_API_KEY}") String apiKey,
			@Value("${postkaro.ai.music-model:fal-ai/lyria2}") String model) {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofSeconds(10));
		factory.setReadTimeout(Duration.ofSeconds(60));

		this.model = model;
		this.restClient = RestClient.builder().requestFactory(factory)
				.defaultHeader(HttpHeaders.AUTHORIZATION, "Key " + apiKey)
				.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).build();
	}

	/**
	 * @param mood from the reel plan, e.g. "upbeat indian lo-fi with tabla, warm"
	 * @param bpm  tempo the cuts are timed to
	 * @return URL of the generated track (hosted by fal)
	 */
	public String compose(String mood, int bpm) {
		String prompt = (mood == null || mood.isBlank() ? "upbeat, warm, modern" : mood)
				+ ". Instrumental only, no vocals. " + bpm + " BPM with a clear, steady beat."
				+ " Starts with energy from the very first second — no slow intro."
				+ " Catchy, polished, modern production for a short Instagram Reel.";

		Map<String, Object> body = new HashMap<>();
		body.put("prompt", prompt);
		body.put("negative_prompt", NEGATIVE);

		JsonNode submitted = restClient.post().uri(QUEUE_URL + "/" + model).body(body).retrieve()
				.onStatus(HttpStatusCode::isError, (req, res) -> {
					throw new FalImageClient.FalException("fal music submit " + res.getStatusCode().value() + ": "
							+ new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8));
				}).body(JsonNode.class);

		if (submitted == null || !submitted.hasNonNull("status_url") || !submitted.hasNonNull("response_url")) {
			throw new FalImageClient.FalException("fal music submit returned no request: " + submitted);
		}
		String statusUrl = submitted.get("status_url").asText();
		String responseUrl = submitted.get("response_url").asText();

		long deadline = System.currentTimeMillis() + GIVE_UP_AFTER.toMillis();
		while (true) {
			if (System.currentTimeMillis() > deadline) {
				throw new FalImageClient.FalException("fal music timed out");
			}
			try {
				Thread.sleep(POLL_EVERY.toMillis());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new FalImageClient.FalException("Interrupted while waiting for music");
			}

			JsonNode status = restClient.get().uri(statusUrl).retrieve().body(JsonNode.class);
			String state = status == null ? "" : status.path("status").asText("");
			if ("COMPLETED".equals(state)) {
				break;
			}
			if (!"IN_QUEUE".equals(state) && !"IN_PROGRESS".equals(state)) {
				throw new FalImageClient.FalException("fal music failed: " + status);
			}
		}

		JsonNode result = restClient.get().uri(responseUrl).retrieve().body(JsonNode.class);
		String url = result == null ? "" : result.path("audio").path("url").asText("");
		if (url.isBlank()) {
			throw new FalImageClient.FalException("fal music returned no audio: " + result);
		}
		log.info("Composed reel music ({} BPM)", bpm);
		return url;
	}
}