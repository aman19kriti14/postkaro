package in.postkaro.controller;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import in.postkaro.dto.request.AnalyticsDtos.AnalyticsResponse;
import in.postkaro.entity.User;
import in.postkaro.repository.UserRepository;
import in.postkaro.service.AnalyticsService;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

	private final AnalyticsService analytics;
	private final UserRepository users;

	/**
	 * GET /api/v1/analytics?period=30d&channel=instagram period: 7d | 30d |
	 * campaign (needs campaignId) | custom (needs from & to, yyyy-MM-dd) channel:
	 * all | instagram | facebook | linkedin | youtube | x
	 */
	@GetMapping
	public AnalyticsResponse get(Authentication auth, @RequestParam(defaultValue = "30d") String period,
			@RequestParam(required = false) UUID campaignId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(defaultValue = "all") String channel) {
		return analytics.get(userId(auth), period, campaignId, from, to, channel);
	}

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