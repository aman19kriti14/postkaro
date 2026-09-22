package in.postkaro.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import in.postkaro.dto.response.AdminInsightsDtos.Insights;
import in.postkaro.dto.response.ApiResponse;
import in.postkaro.service.AdminInsightsService;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/admin/insights")
@RequiredArgsConstructor
public class AdminInsightsController {

	private final AdminInsightsService insights;

	@Value("${app.admin.token:}")
	private String adminToken;

	/** Everything at once: the five lists plus a headcount. */
	@GetMapping
	public ResponseEntity<ApiResponse<Insights>> get(
			@RequestHeader(value = "X-Admin-Token", required = false) String token,
			@RequestParam(defaultValue = "100") int limit) {

		requireAdmin(token);
		return ResponseEntity.ok(ApiResponse.ok(insights.collect(Math.min(500, limit)), "OK"));
	}

	/** Send yourself the digest right now, without waiting for the morning run. */
	@PostMapping("/send-digest")
	public ResponseEntity<ApiResponse<String>> sendNow(
			@RequestHeader(value = "X-Admin-Token", required = false) String token) {

		requireAdmin(token);
		insights.sendDigest(true);
		return ResponseEntity.ok(ApiResponse.ok("sent", "Digest queued."));
	}

	private void requireAdmin(String token) {
		if (adminToken == null || adminToken.isBlank() || !adminToken.equals(token)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin token missing or wrong.");
		}
	}
}