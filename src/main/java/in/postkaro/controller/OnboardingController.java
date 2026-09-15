package in.postkaro.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import in.postkaro.dto.response.ApiResponse;
import in.postkaro.entity.User;
import in.postkaro.service.OnboardingService;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/onboarding")
@RequiredArgsConstructor
public class OnboardingController {

	private final OnboardingService onboardingService;

	@PostMapping("/profile")
	public ResponseEntity<ApiResponse<Void>> saveProfile(@AuthenticationPrincipal User user,
			@RequestBody Map<String, Object> body) {
		System.out.println("BODY KEYS: " + body.keySet());
		System.out.println("BODY: " + body);
		// Validate required fields
		String[] required = { "name", "phone", "userType", "brandName", "category", "sourceType", "teamSize" };
		for (String field : required) {
			Object val = body.get(field);
			if (val == null || val.toString().isBlank()) {
				return ResponseEntity.badRequest().body(ApiResponse.error(field + " is required"));
			}
		}

		onboardingService.saveProfile(user, body);
		return ResponseEntity.ok(ApiResponse.ok(null, "Profile saved."));
	}
}