package in.postkaro.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import in.postkaro.dto.request.BrandDtos.BrandUpdate;
import in.postkaro.dto.request.BrandDtos.BrandView;
import in.postkaro.entity.User;
import in.postkaro.repository.UserRepository;
import in.postkaro.service.BrandSettingsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/settings")
@RequiredArgsConstructor
public class SettingsController {

	private final BrandSettingsService brand;
	private final UserRepository users;

	// GET /api/v1/settings/brand
	@GetMapping("/brand")
	public BrandView getBrand(Authentication auth) {
		return brand.get(userId(auth));
	}

	// PUT /api/v1/settings/brand (full replace — the "Save changes" button)
	@PutMapping("/brand")
	public BrandView updateBrand(Authentication auth, @Valid @RequestBody BrandUpdate body) {
		return brand.update(userId(auth), body);
	}

	// GET /api/v1/settings/brand/options → allowed tones, languages, fonts for the
	// UI
	@GetMapping("/brand/options")
	public Map<String, List<String>> brandOptions() {
		return Map.of("tones", BrandSettingsService.TONES, "languages", BrandSettingsService.LANGUAGES, "fonts",
				BrandSettingsService.FONTS);
	}

	// ---------- helpers ----------

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