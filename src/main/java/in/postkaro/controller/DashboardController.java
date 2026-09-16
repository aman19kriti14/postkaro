package in.postkaro.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import in.postkaro.dto.response.DashboardResponse;
import in.postkaro.entity.User;
import in.postkaro.repository.UserRepository;
import in.postkaro.service.DashboardService;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

	private final DashboardService dashboardService;
	private final UserRepository users;

	// GET /api/v1/dashboard
	@GetMapping
	public DashboardResponse get(Authentication auth) {
		return dashboardService.get(currentUserId(auth));
	}

	private UUID currentUserId(Authentication auth) {
		if (auth == null || !auth.isAuthenticated()) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		}
		// Case 1: JWT filter stores the User entity as principal
		if (auth.getPrincipal() instanceof User u) {
			return u.getId();
		}
		// Case 2: principal is an email / UserDetails — look it up
		return users.findByEmail(auth.getName()).map(User::getId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
	}
}