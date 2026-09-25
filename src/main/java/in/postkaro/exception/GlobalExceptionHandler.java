package in.postkaro.exception;

import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import in.postkaro.dto.response.ApiResponse;
import in.postkaro.service.OtpService;

@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(DuplicateEmailException.class)
	public ResponseEntity<ApiResponse<Void>> handleDuplicateEmail(DuplicateEmailException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(ex.getMessage()));
	}

	@ExceptionHandler(InvalidTokenException.class)
	public ResponseEntity<ApiResponse<Void>> handleInvalidToken(InvalidTokenException ex) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error(ex.getMessage()));
	}

	@ExceptionHandler(BadCredentialsException.class)
	public ResponseEntity<ApiResponse<Void>> handleBadCredentials(BadCredentialsException ex) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("Invalid email or password."));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
		String message = ex.getBindingResult().getFieldErrors().stream().map(FieldError::getDefaultMessage)
				.collect(Collectors.joining(", "));
		return ResponseEntity.badRequest().body(ApiResponse.error(message));
	}

	// Keeps the real status (404, 409, 422...) and message instead of turning it
	// into a 500
	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<ApiResponse<Void>> handleStatus(ResponseStatusException ex) {
		String msg = ex.getReason() != null ? ex.getReason() : ex.getStatusCode().toString();
		return ResponseEntity.status(ex.getStatusCode()).body(ApiResponse.error(msg));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception ex) {
		System.out.println("ERROR: " + ex.getClass().getName() + " - " + ex.getMessage());
		ex.printStackTrace();
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(ApiResponse.error("Error: " + ex.getMessage()));
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException ex) {
		return ResponseEntity.badRequest().body(ApiResponse.error(ex.getMessage()));
	}

	@ExceptionHandler(SubscriptionRequiredException.class)
	public ResponseEntity<ApiResponse<Map<String, Object>>> handleSubscriptionRequired(
			SubscriptionRequiredException ex) {
		return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
				.body(ApiResponse.<Map<String, Object>>builder().success(false).message(ex.getMessage())
						.data(Map.of("code", "TRIAL_EXPIRED")).timestamp(java.time.Instant.now()).build());
	}

	@ExceptionHandler(InsufficientCreditsException.class)
	public ResponseEntity<ApiResponse<Map<String, Object>>> handleInsufficientCredits(InsufficientCreditsException ex) {
		return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
				.body(ApiResponse
						.<Map<String, Object>>builder().success(false).message(ex.getMessage()).data(Map.of("code",
								"INSUFFICIENT_CREDITS", "required", ex.getRequired(), "available", ex.getAvailable()))
						.timestamp(java.time.Instant.now()).build());
	}

	@ExceptionHandler(OtpService.OtpException.class)
	public ResponseEntity<ApiResponse<Void>> handleOtp(OtpService.OtpException ex) {
		return ResponseEntity.badRequest().body(ApiResponse.error(ex.getMessage()));
	}
}