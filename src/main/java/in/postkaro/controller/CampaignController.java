package in.postkaro.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import in.postkaro.dto.response.ApiResponse;
import in.postkaro.entity.User;
import in.postkaro.service.CampaignPlanService;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/campaigns")
@RequiredArgsConstructor
public class CampaignController {

	private final CampaignPlanService planService;

	@PostMapping("/plan")
	public ResponseEntity<ApiResponse<List<Map<String, Object>>>> plan(@AuthenticationPrincipal User user,
			@RequestBody Map<String, Object> body) {
		return ResponseEntity.ok(ApiResponse.ok(planService.buildPlan(body), "Plan ready."));
	}
}