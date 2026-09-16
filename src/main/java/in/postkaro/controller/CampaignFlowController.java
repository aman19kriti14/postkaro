package in.postkaro.controller;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import in.postkaro.dto.response.ApiResponse;
import in.postkaro.dto.response.CampaignFlowResponse;
import in.postkaro.dto.response.CampaignFlowResponse.Checks;
import in.postkaro.dto.response.CampaignFlowResponse.FlowPost;
import in.postkaro.entity.User;
import in.postkaro.repository.UserRepository;
import in.postkaro.service.CampaignFlowService;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/campaigns")
@RequiredArgsConstructor
public class CampaignFlowController {

	private final CampaignFlowService flowService;
	private final UserRepository userRepository;

	// Start a new campaign draft
	@PostMapping("/drafts")
	public ResponseEntity<ApiResponse<CampaignFlowResponse>> createDraft(@AuthenticationPrincipal User user) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(ApiResponse.ok(flowService.createDraft(user), "Draft created."));
	}

	// Full builder state, used to resume
	@GetMapping("/{id}/flow")
	public ResponseEntity<ApiResponse<CampaignFlowResponse>> get(@AuthenticationPrincipal User user,
			@PathVariable UUID id) {
		return ResponseEntity.ok(ApiResponse.ok(flowService.get(user.getId(), id), "OK"));
	}

	// Autosave brief fields (partial)
	@PatchMapping("/{id}/brief")
	public ResponseEntity<ApiResponse<CampaignFlowResponse>> saveBrief(@AuthenticationPrincipal User user,
			@PathVariable UUID id, @RequestBody Map<String, Object> body) {
		return ResponseEntity.ok(ApiResponse.ok(flowService.saveBrief(user.getId(), id, body), "Saved."));
	}

	// { "step": 3 }
	@PatchMapping("/{id}/step")
	public ResponseEntity<Void> saveStep(@AuthenticationPrincipal User user, @PathVariable UUID id,
			@RequestBody Map<String, Object> body) {
		Object step = body.get("step");
		int value = step instanceof Number n ? n.intValue() : 1;
		flowService.saveStep(user.getId(), id, value);
		return ResponseEntity.noContent().build();
	}

	// Generate (or regenerate) the plan and save it as draft posts
	@PostMapping("/{id}/generate-plan")
	public ResponseEntity<ApiResponse<CampaignFlowResponse>> generatePlan(@AuthenticationPrincipal User user,
			@PathVariable UUID id) {
		return ResponseEntity.ok(ApiResponse.ok(flowService.generatePlan(user.getId(), id), "Plan ready."));
	}

	// Edit, approve, move or set visual on one post (partial)
	@PatchMapping("/{id}/posts/{postId}")
	public ResponseEntity<ApiResponse<FlowPost>> updatePost(@AuthenticationPrincipal User user, @PathVariable UUID id,
			@PathVariable UUID postId, @RequestBody Map<String, Object> body) {
		return ResponseEntity.ok(ApiResponse.ok(flowService.updatePost(user.getId(), id, postId, body), "Saved."));
	}

	@PostMapping("/{id}/approve-all")
	public ResponseEntity<ApiResponse<CampaignFlowResponse>> approveAll(@AuthenticationPrincipal User user,
			@PathVariable UUID id) {
		return ResponseEntity.ok(ApiResponse.ok(flowService.approveAll(user.getId(), id), "All approved."));
	}

	@GetMapping("/{id}/checks")
	public ResponseEntity<ApiResponse<Checks>> checks(@AuthenticationPrincipal User user, @PathVariable UUID id) {
		return ResponseEntity.ok(ApiResponse.ok(flowService.checks(user.getId(), id, connectedPlatforms(user)), "OK"));
	}

	@PostMapping("/{id}/publish")
	public ResponseEntity<ApiResponse<CampaignFlowResponse>> publish(@AuthenticationPrincipal User user,
			@PathVariable UUID id) {
		return ResponseEntity.ok(
				ApiResponse.ok(flowService.publish(user.getId(), id, connectedPlatforms(user)), "Campaign scheduled."));
	}

	// Same lookup /auth/me uses; adjust getter names if yours differ
	private Set<String> connectedPlatforms(User user) {
		User fresh = userRepository.findByIdWithAccounts(user.getId()).orElseThrow();
		return fresh.getConnectedAccounts().stream().map(a -> a.getPlatform().toString().toLowerCase())
				.collect(Collectors.toSet());
	}
}