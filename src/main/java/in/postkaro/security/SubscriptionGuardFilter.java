package in.postkaro.security;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;

import in.postkaro.dto.response.ApiResponse;
import in.postkaro.entity.User;
import in.postkaro.service.CreditService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * Once the trial (or paid period) ends, the account becomes read-only: GET
 * requests still work, anything that creates, generates or publishes gets 402.
 */
@Component
@RequiredArgsConstructor
public class SubscriptionGuardFilter extends OncePerRequestFilter {

	private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

	/** Writes that stay allowed after expiry, so users can log out and pay. */
	private static final List<String> ALWAYS_ALLOWED = List.of("/api/v1/auth/", "/api/v1/billing/", "/api/v1/admin/");

	private final CreditService creditService;
	private final ObjectMapper objectMapper;

	@Override
	protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
			@NonNull FilterChain chain) throws ServletException, IOException {

		User user = currentUser();

		if (user == null || READ_METHODS.contains(request.getMethod()) || isAlwaysAllowed(request.getRequestURI())) {
			chain.doFilter(request, response);
			return;
		}

		if (creditService.hasAccess(user.getId())) {
			chain.doFilter(request, response);
			return;
		}

		response.setStatus(HttpStatus.PAYMENT_REQUIRED.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		objectMapper.writeValue(response.getWriter(),
				ApiResponse.<Map<String, Object>>builder().success(false)
						.message("Your free trial has ended. Choose a plan to keep creating and publishing.")
						.data(Map.of("code", "TRIAL_EXPIRED")).timestamp(Instant.now()).build());
	}

	private boolean isAlwaysAllowed(String uri) {
		return ALWAYS_ALLOWED.stream().anyMatch(uri::startsWith);
	}

	private User currentUser() {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		return (auth != null && auth.getPrincipal() instanceof User u) ? u : null;
	}
}