package in.postkaro.service;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MediaService {

	@Value("${fal.api.key:}")
	private String falApiKey;

	private final RestClient restClient = RestClient.create();

	@SuppressWarnings("unchecked")
	public Map<String, Object> generateImage(String prompt, String size) {
		String imageSize = "square_hd"; // 1024x1024
		if ("landscape".equals(size))
			imageSize = "landscape_16_9";
		if ("portrait".equals(size))
			imageSize = "portrait_16_9";

		Map<String, Object> body = Map.of("prompt", prompt, "image_size", imageSize, "num_images", 1,
				"enable_safety_checker", true);

		Map<String, Object> response = restClient.post().uri("https://fal.run/fal-ai/flux/schnell")
				.header("Authorization", "Key " + falApiKey).contentType(MediaType.APPLICATION_JSON).body(body)
				.retrieve().body(Map.class);

		List<Map<String, Object>> images = (List<Map<String, Object>>) response.get("images");
		if (images != null && !images.isEmpty()) {
			return Map.of("url", images.get(0).get("url"), "width", images.get(0).getOrDefault("width", 1024), "height",
					images.get(0).getOrDefault("height", 1024));
		}
		throw new RuntimeException("No image generated");
	}

	@SuppressWarnings("unchecked")
	public Map<String, Object> generateVideo(String prompt) {
		Map<String, Object> body = Map.of("prompt", prompt, "num_frames", 49, "fps", 8, "resolution", "512");

		// Submit request
		Map<String, Object> submitResponse = restClient.post()
				.uri("https://fal.run/fal-ai/fast-animatediff/text-to-video")
				.header("Authorization", "Key " + falApiKey).contentType(MediaType.APPLICATION_JSON).body(body)
				.retrieve().body(Map.class);

		if (submitResponse != null && submitResponse.containsKey("video")) {
			Map<String, Object> video = (Map<String, Object>) submitResponse.get("video");
			return Map.of("url", video.get("url"), "type", "video");
		}
		throw new RuntimeException("No video generated");
	}
}