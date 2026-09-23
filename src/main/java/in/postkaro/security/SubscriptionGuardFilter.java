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
 * Keeps an account read-only when it isn't allowed to create: email not yet
 * verified (403 EMAIL_NOT_VERIFIED), or trial/plan ended (402 TRIAL_EXPIRED).
 * GET requests always pass, so users can still see their work.
 */
@Component
@RequiredArgsConstructor
public class SubscriptionGuardFilter extends OncePerRequestFilter {

	private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

	/** Writes that stay allowed, so users can verify, log out and pay. */
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

		if (!user.isEmailVerified()) {
			reject(response, HttpStatus.FORBIDDEN, "EMAIL_NOT_VERIFIED", "Verify your email to start creating.");
			return;
		}

		if (creditService.hasAccess(user.getId())) {
			chain.doFilter(request, response);
			return;
		}

		reject(response, HttpStatus.PAYMENT_REQUIRED, "TRIAL_EXPIRED",
				"Your free trial has ended. Choose a plan to keep creating and publishing.");
	}

	private void reject(HttpServletResponse response, HttpStatus status, String code, String message)
			throws IOException {
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		objectMapper.writeValue(response.getWriter(), ApiResponse.<Map<String, Object>>builder().success(false)
				.message(message).data(Map.of("code", code)).timestamp(Instant.now()).build());
	}

	private boolean isAlwaysAllowed(String uri) {
		return ALWAYS_ALLOWED.stream().anyMatch(uri::startsWith);
	}

	private User currentUser() {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		return (auth != null && auth.getPrincipal() instanceof User u) ? u : null;
	}
}