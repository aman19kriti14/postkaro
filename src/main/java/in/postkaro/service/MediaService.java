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
public class MediaService {

	@Value("${fal.api.key:}")
	private String falApiKey;

	@Value("${openai.api.key:}")
	private String openaiApiKey;

	private final RestClient restClient = RestClient.create();

	@SuppressWarnings("unchecked")
	public Map<String, Object> generateImage(String userPrompt, String size) {
		// Step 1: Use OpenAI to create a proper image prompt
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
			return Map.of("url", images.get(0).get("url"), "width", images.get(0).getOrDefault("width", 1024), "height",
					images.get(0).getOrDefault("height", 1024), "prompt", imagePrompt);
		}
		throw new RuntimeException("No image generated");
	}

	@SuppressWarnings("unchecked")
	public Map<String, Object> generateVideo(String userPrompt) {
		String videoPrompt = craftVideoPrompt(userPrompt);
		System.out.println("VIDEO PROMPT: " + videoPrompt);

		Map<String, Object> body = Map.of("prompt", videoPrompt, "num_frames", 49, "fps", 8, "resolution", "512");

		Map<String, Object> response = restClient.post().uri("https://fal.run/fal-ai/fast-animatediff/text-to-video")
				.header("Authorization", "Key " + falApiKey).contentType(MediaType.APPLICATION_JSON).body(body)
				.retrieve().body(Map.class);

		if (response != null && response.containsKey("video")) {
			Map<String, Object> video = (Map<String, Object>) response.get("video");
			return Map.of("url", video.get("url"), "type", "video", "prompt", videoPrompt);
		}
		throw new RuntimeException("No video generated");
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
						- Focus on one subject with gentle motion (steam rising, leaves falling, product rotating)
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
}