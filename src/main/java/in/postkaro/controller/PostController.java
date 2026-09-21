package in.postkaro.controller;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import in.postkaro.dto.response.ApiResponse;
import in.postkaro.dto.response.PosterCopy;
import in.postkaro.entity.Language;
import in.postkaro.entity.Post;
import in.postkaro.entity.User;
import in.postkaro.service.AiService;
import in.postkaro.service.BrandSettingsService;
import in.postkaro.service.MediaService;
import in.postkaro.service.PostService;
import in.postkaro.service.PublishService;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/posts")
@RequiredArgsConstructor
public class PostController {

	private final PostService postService;
	private final AiService aiService;
	private final MediaService mediaService;
	private final PublishService publishService;
	private final BrandSettingsService brandSettings;

	@PostMapping("/generate-caption")
	public ResponseEntity<ApiResponse<Map<String, String>>> generateCaption(@AuthenticationPrincipal User user,
			@RequestBody Map<String, Object> body) {

		String prompt = (String) body.get("prompt");
		String tone = (String) body.getOrDefault("tone", "warm");
		List<String> channels = (List<String>) body.getOrDefault("channels", List.of("instagram"));
		Language language = parseLanguage((String) body.get("language"));

		// Add the saved brand voice to the brief; unchanged if none is set
		String voice = brandSettings.promptContext(user.getId());
		String briefWithVoice = voice.isBlank() ? prompt : prompt + "\n\nBrand voice (follow strictly):\n" + voice;

		String caption = aiService.generateCaption(briefWithVoice, tone, channels, language);
		return ResponseEntity.ok(ApiResponse.ok(Map.of("caption", caption), "Caption generated."));
	}

	@PostMapping("/refine-caption")
	public ResponseEntity<ApiResponse<Map<String, String>>> refineCaption(@AuthenticationPrincipal User user,
			@RequestBody Map<String, Object> body) {

		String caption = (String) body.get("caption");
		String action = (String) body.get("action");
		Language language = parseLanguage((String) body.get("language"));

		String refined = aiService.refineCaption(caption, action, language);
		return ResponseEntity.ok(ApiResponse.ok(Map.of("caption", refined), "Caption refined."));
	}

	@PostMapping("/generate-poster-copy")
	public ResponseEntity<ApiResponse<PosterCopy>> generatePosterCopy(@AuthenticationPrincipal User user,
			@RequestBody Map<String, Object> body) {

		String topic = (String) body.get("topic");
		String brandName = (String) body.get("brandName");
		Language language = parseLanguage((String) body.get("language"));

		PosterCopy copy = aiService.generatePosterCopy(topic, brandName, language);
		return ResponseEntity.ok(ApiResponse.ok(copy, "Poster copy generated."));
	}

	private Language parseLanguage(String raw) {
		if (raw == null || raw.isBlank()) {
			return Language.ENGLISH;
		}
		try {
			return Language.valueOf(raw.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			return Language.ENGLISH;
		}
	}

	@PostMapping("/draft")
	public ResponseEntity<ApiResponse<Map<String, Object>>> saveDraft(@AuthenticationPrincipal User user,
			@RequestBody Map<String, Object> body) {

		Post post = postService.createDraft(user, body);
		Map<String, Object> result = new HashMap<>();
		result.put("id", post.getId().toString());
		result.put("status", post.getStatus().name());
		return ResponseEntity.ok(ApiResponse.ok(result, "Draft saved."));
	}

	@PutMapping("/{id}")
	public ResponseEntity<ApiResponse<Map<String, Object>>> updatePost(@AuthenticationPrincipal User user,
			@PathVariable UUID id, @RequestBody Map<String, Object> body) {

		Post post = postService.updatePost(id, user.getId(), body);
		Map<String, Object> result = new HashMap<>();
		result.put("id", post.getId().toString());
		result.put("status", post.getStatus().name());
		return ResponseEntity.ok(ApiResponse.ok(result, "Post updated."));
	}

	@PostMapping("/{id}/schedule")
	public ResponseEntity<ApiResponse<Map<String, Object>>> schedulePost(@AuthenticationPrincipal User user,
			@PathVariable UUID id, @RequestBody Map<String, Object> body) {

		String scheduledAtStr = (String) body.get("scheduledAt");
		Instant scheduledAt = Instant.parse(scheduledAtStr);

		Post post = postService.schedulePost(id, user.getId(), scheduledAt);
		Map<String, Object> result = new HashMap<>();
		result.put("id", post.getId().toString());
		result.put("status", post.getStatus().name());
		result.put("scheduledAt", post.getScheduledAt().toString());
		return ResponseEntity.ok(ApiResponse.ok(result, "Post scheduled."));
	}

	@GetMapping
	public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listPosts(@AuthenticationPrincipal User user) {

		List<Post> posts = postService.getUserPosts(user.getId());
		List<Map<String, Object>> result = posts.stream().map(p -> {
			Map<String, Object> m = new HashMap<>();
			m.put("id", p.getId().toString());
			m.put("caption", p.getCaption());
			m.put("status", p.getStatus().name());
			m.put("channels", p.getChannels());
			m.put("scheduledAt", p.getScheduledAt());
			m.put("createdAt", p.getCreatedAt());
			return m;
		}).toList();

		return ResponseEntity.ok(ApiResponse.ok(result, "OK"));
	}

	@PostMapping("/generate-image")
	public ResponseEntity<ApiResponse<Map<String, Object>>> generateImage(@AuthenticationPrincipal User user,
			@RequestBody Map<String, Object> body) {
		String prompt = (String) body.get("prompt");
		if (prompt == null || prompt.isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "prompt is required");
		}

		List<String> productImageUrls = body.get("productImageUrls") instanceof List<?> l
				? l.stream().filter(String.class::isInstance).map(String.class::cast).toList()
				: List.of();
		Integer variations = body.get("variations") instanceof Number n ? n.intValue() : 1;
		Boolean useLogo = body.get("useLogo") instanceof Boolean b ? b : Boolean.TRUE;

		MediaService.ImageRequest req = new MediaService.ImageRequest(prompt, (String) body.get("size"),
				(String) body.get("aspectRatio"), (String) body.get("contentType"), (String) body.get("language"),
				productImageUrls, useLogo, variations);

		Map<String, Object> result = mediaService.generateImage(user.getId(), req);
		return ResponseEntity.ok(ApiResponse.ok(result, "Image generated."));
	}

	@PostMapping("/generate-video")
	public ResponseEntity<ApiResponse<Map<String, Object>>> generateVideo(@AuthenticationPrincipal User user,
			@RequestBody Map<String, Object> body) {
		String prompt = (String) body.get("prompt");
		Map<String, Object> result = mediaService.generateVideo(prompt);
		return ResponseEntity.ok(ApiResponse.ok(result, "Video generated."));
	}

	@GetMapping("/{id}")
	public ResponseEntity<ApiResponse<Map<String, Object>>> getPost(@AuthenticationPrincipal User user,
			@PathVariable UUID id) {

		Post p = postService.getPost(id, user.getId());

		Map<String, Object> result = new HashMap<>();
		result.put("id", p.getId().toString());
		result.put("caption", p.getCaption());
		result.put("prompt", p.getPrompt());
		result.put("tone", p.getTone());
		result.put("status", p.getStatus().name());
		result.put("channels", p.getChannels());
		result.put("scheduledAt", p.getScheduledAt());
		result.put("media", p.getMedia().stream().map(m -> {
			Map<String, Object> mm = new HashMap<>();
			mm.put("url", m.getUrl());
			mm.put("type", m.getType());
			return mm;
		}).toList());

		return ResponseEntity.ok(ApiResponse.ok(result, "OK"));
	}

	@PostMapping("/{id}/publish")
	public ResponseEntity<ApiResponse<Map<String, Object>>> publishPost(@AuthenticationPrincipal User user,
			@PathVariable UUID id) {
		PublishService.PublishResult result = publishService.publishPost(id, user.getId());
		if (!result.success()) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, result.error());
		}
		return ResponseEntity.ok(ApiResponse.ok(Map.of("published", result.published()), "Post published."));
	}
}