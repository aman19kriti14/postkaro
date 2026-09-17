package in.postkaro.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class SarvamClient {

	private static final String BASE_URL = "https://api.sarvam.ai";
	private static final String MODEL = "sarvam-105b";

	private final RestClient restClient;

	public SarvamClient(@Value("${sarvam.api-key}") String apiKey) {
		this.restClient = RestClient.builder().baseUrl(BASE_URL).defaultHeader("api-subscription-key", apiKey)
				// If /v2 rejects the above, swap for:
				// .defaultHeader("Authorization", "Bearer " + apiKey)
				.defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE).build();
	}

	public String complete(String systemPrompt, String userPrompt) {
		Map<String, Object> body = new HashMap<>();
		body.put("model", MODEL);
		body.put("messages", List.of(Map.of("role", "system", "content", systemPrompt),
				Map.of("role", "user", "content", userPrompt)));
		body.put("temperature", 0.7);
		body.put("max_tokens", 800);
		body.put("reasoning_effort", null); // caption writing needs no reasoning trace

		try {
			Map<?, ?> response = restClient.post().uri("/v1/chat/completions").body(body).retrieve().body(Map.class);

			return extractContent(response);
		} catch (Exception e) {
			log.error("Sarvam completion failed", e);
			throw new IllegalStateException("Could not generate content in this language. Please try again.", e);
		}
	}

	@SuppressWarnings("unchecked")
	private String extractContent(Map<?, ?> response) {
		if (response == null) {
			throw new IllegalStateException("Empty response from Sarvam");
		}
		List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
		if (choices == null || choices.isEmpty()) {
			throw new IllegalStateException("No choices in Sarvam response");
		}

		String finishReason = (String) choices.get(0).get("finish_reason");
		Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
		String content = message == null ? null : (String) message.get("content");

		if (content == null || content.isBlank()) {
			if ("length".equals(finishReason)) {
				throw new IllegalStateException(
						"Sarvam hit the token limit before producing content — reasoning may still be enabled");
			}
			throw new IllegalStateException("Blank content in Sarvam response");
		}
		return content.trim();
	}
}