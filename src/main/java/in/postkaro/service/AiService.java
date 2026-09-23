package in.postkaro.service;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import in.postkaro.dto.response.PosterCopy;
import in.postkaro.entity.Language;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AiService {

	@Value("${openai.api.key:}")
	private String openaiApiKey;

	@Value("${postkaro.ai.caption-model:gpt-4o-mini}")
	private String captionModel;

	private final RestClient restClient = RestClient.create();

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final SarvamClient sarvamClient;
	private final CaptionPromptBuilder promptBuilder;

	public String generateCaption(String prompt, String tone, List<String> channels, Language language) {
		String systemPrompt = promptBuilder.system(language, tone, channels);

		if (language.isUseIndicModel()) {
			return sarvamClient.complete(systemPrompt, promptBuilder.user(prompt, null));
		}
		return callOpenAi(systemPrompt, prompt, 0.8);
	}

	public String refineCaption(String caption, String action, Language language) {
		String instruction = switch (action) {
		case "shorter" -> "Make this caption shorter and punchier. Keep the core message.";
		case "hashtags" -> "Add 5-8 relevant hashtags to this caption. Keep the caption as-is.";
		case "playful" -> "Rewrite this caption to be more playful and fun. Keep the same information.";
		default -> "Improve this caption.";
		};

		String systemPrompt = "You are a social media copywriter. Follow the instruction exactly. "
				+ "Keep the caption in " + language.getDisplayName() + " — do not change its language. "
				+ "Return only the caption text, with no preamble.";

		String userPrompt = instruction + "\n\nCaption:\n" + caption;

		if (language.isUseIndicModel()) {
			return sarvamClient.complete(systemPrompt, userPrompt);
		}
		return callOpenAi(systemPrompt, userPrompt, 0.7);
	}

	@SuppressWarnings("unchecked")
	private String callOpenAi(String systemPrompt, String userPrompt, double temperature) {
		Map<String, Object> body = new java.util.HashMap<>();
		body.put("model", captionModel);
		body.put("messages", List.of(Map.of("role", "system", "content", systemPrompt),
				Map.of("role", "user", "content", userPrompt)));

		if (captionModel.startsWith("gpt-4")) {
			body.put("max_tokens", 500);
			body.put("temperature", temperature);
		} else {
			// GPT-5/6 and o-series: reasoning models
			body.put("max_completion_tokens", 4000);
			body.put("reasoning_effort", "low");
		}

		Map<String, Object> response = restClient.post().uri("https://api.openai.com/v1/chat/completions")
				.header("Authorization", "Bearer " + openaiApiKey).contentType(MediaType.APPLICATION_JSON).body(body)
				.retrieve().body(Map.class);

		List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
		Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
		return ((String) message.get("content")).trim();
	}

	public PosterCopy generatePosterCopy(String topic, String brandName, Language language) {
		String systemPrompt = promptBuilder.posterSystem(language);
		String userPrompt = promptBuilder.user(topic, brandName);

		String raw = language.isUseIndicModel() ? sarvamClient.complete(systemPrompt, userPrompt)
				: callOpenAi(systemPrompt, userPrompt, 0.8);

		return parsePosterCopy(raw);
	}

	private PosterCopy parsePosterCopy(String raw) {
		String cleaned = raw.trim();

		// Models often wrap JSON in code fences despite being told not to
		int start = cleaned.indexOf('{');
		int end = cleaned.lastIndexOf('}');
		if (start >= 0 && end > start) {
			cleaned = cleaned.substring(start, end + 1);
		}

		try {
			return objectMapper.readValue(cleaned, PosterCopy.class);
		} catch (Exception e) {
			throw new IllegalStateException("Could not generate poster copy. Please try again.", e);
		}
	}
}