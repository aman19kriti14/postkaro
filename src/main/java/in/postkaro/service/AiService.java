package in.postkaro.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AiService {

	@Value("${openai.api.key:}")
	private String openaiApiKey;

	private final RestClient restClient = RestClient.create();

	public String generateCaption(String prompt, String tone, List<String> channels) {
		String systemPrompt = """
				You are a social media copywriter for Indian creators and small businesses.
				Write a caption based on the user's brief.

				Rules:
				- Tone: %s
				- Target platforms: %s
				- Keep it under 300 characters for Twitter/X, up to 2200 for Instagram
				- Use line breaks for readability
				- Include 3-5 relevant hashtags at the end
				- Write in the user's voice — natural, not corporate
				- If the brief is in Hindi/Hinglish, respond in the same language
				""".formatted(tone, String.join(", ", channels));

		Map<String, Object> body = Map.of("model", "gpt-4o-mini", "messages",
				List.of(Map.of("role", "system", "content", systemPrompt), Map.of("role", "user", "content", prompt)),
				"max_tokens", 500, "temperature", 0.8);

		Map<String, Object> response = restClient.post().uri("https://api.openai.com/v1/chat/completions")
				.header("Authorization", "Bearer " + openaiApiKey).contentType(MediaType.APPLICATION_JSON).body(body)
				.retrieve().body(Map.class);

		List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
		Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
		return (String) message.get("content");
	}

	public String refineCaption(String caption, String action) {
		String instruction = switch (action) {
		case "shorter" -> "Make this caption shorter and punchier. Keep the core message.";
		case "hashtags" -> "Add 5-8 relevant hashtags to this caption. Keep the caption as-is.";
		case "playful" -> "Rewrite this caption to be more playful and fun. Keep the same information.";
		case "hindi" -> "Translate this caption to Hindi. Keep hashtags in English.";
		default -> "Improve this caption.";
		};

		Map<String, Object> body = Map.of("model", "gpt-4o-mini", "messages",
				List.of(Map.of("role", "system", "content",
						"You are a social media copywriter. Follow the instruction exactly."),
						Map.of("role", "user", "content", instruction + "\n\nCaption:\n" + caption)),
				"max_tokens", 500, "temperature", 0.7);

		Map<String, Object> response = restClient.post().uri("https://api.openai.com/v1/chat/completions")
				.header("Authorization", "Bearer " + openaiApiKey).contentType(MediaType.APPLICATION_JSON).body(body)
				.retrieve().body(Map.class);

		List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
		Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
		return (String) message.get("content");
	}
}