package in.postkaro.controller;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import in.postkaro.dto.response.ApiResponse;
import in.postkaro.entity.Post;
import in.postkaro.entity.User;
import in.postkaro.service.AiService;
import in.postkaro.service.PostService;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/posts")
@RequiredArgsConstructor
public class PostController {

	private final PostService postService;
	private final AiService aiService;

	@PostMapping("/generate-caption")
	public ResponseEntity<ApiResponse<Map<String, String>>> generateCaption(@AuthenticationPrincipal User user,
			@RequestBody Map<String, Object> body) {

		String prompt = (String) body.get("prompt");
		String tone = (String) body.getOrDefault("tone", "warm");
		List<String> channels = (List<String>) body.getOrDefault("channels", List.of("instagram"));

		String caption = aiService.generateCaption(prompt, tone, channels);
		return ResponseEntity.ok(ApiResponse.ok(Map.of("caption", caption), "Caption generated."));
	}

	@PostMapping("/refine-caption")
	public ResponseEntity<ApiResponse<Map<String, String>>> refineCaption(@AuthenticationPrincipal User user,
			@RequestBody Map<String, Object> body) {

		String caption = (String) body.get("caption");
		String action = (String) body.get("action");

		String refined = aiService.refineCaption(caption, action);
		return ResponseEntity.ok(ApiResponse.ok(Map.of("caption", refined), "Caption refined."));
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
}