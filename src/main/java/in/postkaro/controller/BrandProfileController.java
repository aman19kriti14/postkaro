package in.postkaro.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import in.postkaro.dto.request.BrandProfileDtos.AnalyzeRequest;
import in.postkaro.dto.request.BrandProfileDtos.ApplyRequest;
import in.postkaro.dto.request.BrandProfileDtos.BrandProfileView;
import in.postkaro.entity.User;
import in.postkaro.enums.CreditAction;
import in.postkaro.repository.UserRepository;
import in.postkaro.service.BrandProfileService;
import in.postkaro.service.CreditService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * "We studied your brand" — website + connected accounts → brand profile +
 * starter prompts.
 *
 * POST /api/v1/brand-profile/analyze → starts in background, returns status GET
 * /api/v1/brand-profile → poll every ~2s while status = RUNNING POST
 * /api/v1/brand-profile/apply → copy findings into Settings › Brand
 */
@RestController
@RequestMapping("/api/v1/brand-profile")
@RequiredArgsConstructor
public class BrandProfileController {

	private final BrandProfileService service;
	private final UserRepository users;
	private final CreditService creditService;

	@GetMapping
	public BrandProfileView get(Authentication auth) {
		return service.get(userId(auth));
	}

	// 3 credits. Refunded only if the job fails to start; it runs in the
	// background.
	@PostMapping("/analyze")
	public BrandProfileView analyze(Authentication auth, @Valid @RequestBody(required = false) AnalyzeRequest body) {
		UUID uid = userId(auth);
		String websiteUrl = body == null ? null : body.websiteUrl();
		return creditService.charge(uid, CreditAction.BRAND_ANALYZE, 1, () -> service.start(uid, websiteUrl));
	}

	@PostMapping("/apply")
	public BrandProfileView apply(Authentication auth, @RequestBody(required = false) ApplyRequest body) {
		return service.apply(userId(auth), body != null && body.overwrite());
	}

	// same pattern as SettingsController
	private UUID userId(Authentication auth) {
		if (auth == null || !auth.isAuthenticated()) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		}
		if (auth.getPrincipal() instanceof User u) {
			return u.getId();
		}
		return users.findByEmail(auth.getName()).map(User::getId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
	}
}