package in.postkaro.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import in.postkaro.dto.response.ApiResponse;
import in.postkaro.entity.Campaign;
import in.postkaro.entity.User;
import in.postkaro.enums.CreditAction;
import in.postkaro.service.CampaignPlanService;
import in.postkaro.service.CampaignService;
import in.postkaro.service.CreditService;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/campaigns")
@RequiredArgsConstructor
public class CampaignController {

	private final CampaignPlanService planService;

	private final CampaignService campaignService;

	private final CreditService creditService;

	// 10 credits
	@PostMapping("/plan")
	public ResponseEntity<ApiResponse<List<Map<String, Object>>>> plan(@AuthenticationPrincipal User user,
			@RequestBody Map<String, Object> body) {
		List<Map<String, Object>> plan = creditService.charge(user.getId(), CreditAction.CAMPAIGN_PLAN, 1,
				() -> planService.buildPlan(body));
		return ResponseEntity.ok(ApiResponse.ok(plan, "Plan ready."));
	}

	@PostMapping
	public ResponseEntity<ApiResponse<Map<String, Object>>> create(@AuthenticationPrincipal User user,
			@RequestBody Map<String, Object> body) {
		Campaign c = campaignService.create(user, body);
		Map<String, Object> result = new HashMap<>();
		result.put("id", c.getId().toString());
		result.put("name", c.getName());
		return ResponseEntity.ok(ApiResponse.ok(result, "Campaign saved."));
	}

	@GetMapping
	public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(@AuthenticationPrincipal User user) {
		return ResponseEntity.ok(ApiResponse.ok(campaignService.list(user.getId()), "OK"));
	}

	@GetMapping("/{id}")
	public ResponseEntity<ApiResponse<Map<String, Object>>> detail(@AuthenticationPrincipal User user,
			@PathVariable UUID id) {
		return ResponseEntity.ok(ApiResponse.ok(campaignService.detail(id, user.getId()), "OK"));
	}
}