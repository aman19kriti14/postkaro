package in.postkaro.controller;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import in.postkaro.dto.response.ApiResponse;
import in.postkaro.entity.Subscription;
import in.postkaro.entity.UpgradeRequest;
import in.postkaro.entity.User;
import in.postkaro.enums.PlanTier;
import in.postkaro.repository.UpgradeRequestRepository;
import in.postkaro.repository.UserRepository;
import in.postkaro.service.CreditService;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/admin/billing")
@RequiredArgsConstructor
public class AdminBillingController {

	private final CreditService creditService;
	private final UserRepository userRepository;
	private final UpgradeRequestRepository upgradeRequests;

	@Value("${app.admin.token:}")
	private String adminToken;

	/** body: { "email": "x@y.com", "plan": "GROWTH" } */
	@PostMapping("/activate")
	public ResponseEntity<ApiResponse<Map<String, Object>>> activate(
			@RequestHeader(value = "X-Admin-Token", required = false) String token,
			@RequestBody Map<String, String> body) {

		requireAdmin(token);
		User target = findUser(body.get("email"));

		String raw = body.get("plan");
		if (raw == null || raw.isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "plan is required");
		}

		PlanTier plan;
		try {
			plan = PlanTier.valueOf(raw.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown plan: " + raw);
		}

		Subscription sub = creditService.activatePlan(target.getId(), plan);
		markHandled(target.getId());
		return ResponseEntity.ok(ApiResponse.ok(summary(sub), "Plan activated."));
	}

	/** body: { "email": "x@y.com", "credits": 500, "note": "Paid top-up" } */
	@PostMapping("/topup")
	public ResponseEntity<ApiResponse<Map<String, Object>>> topup(
			@RequestHeader(value = "X-Admin-Token", required = false) String token,
			@RequestBody Map<String, Object> body) {

		requireAdmin(token);
		User target = findUser((String) body.get("email"));
		int credits = body.get("credits") instanceof Number n ? n.intValue() : 0;
		String note = (String) body.getOrDefault("note", "Manual top-up");

		if (credits <= 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "credits must be greater than 0");
		}

		Subscription sub = creditService.addTopup(target.getId(), credits, note);
		markHandled(target.getId());
		return ResponseEntity.ok(ApiResponse.ok(summary(sub), "Credits added."));
	}

	private void requireAdmin(String token) {
		if (adminToken == null || adminToken.isBlank() || !adminToken.equals(token)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin token missing or wrong.");
		}
	}

	private User findUser(String email) {
		if (email == null || email.isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "email is required");
		}
		return userRepository.findByEmail(email.toLowerCase().trim())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No user with that email."));
	}

	private Map<String, Object> summary(Subscription sub) {
		return Map.of("plan", sub.getPlan().name(), "status", sub.getStatus().name(), "credits", sub.totalCredits(),
				"periodEnd", sub.getCurrentPeriodEnd().toString());
	}

	private void markHandled(UUID userId) {
		upgradeRequests.findFirstByUserIdAndStatusOrderByCreatedAtDesc(userId, UpgradeRequest.Status.PENDING)
				.ifPresent(r -> {
					r.setStatus(UpgradeRequest.Status.DONE);
					r.setHandledAt(Instant.now());
					upgradeRequests.save(r);
				});
	}
}