package in.postkaro.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import in.postkaro.dto.request.LogoutRequest;
import in.postkaro.dto.request.OtpDtos.ForgotPasswordRequest;
import in.postkaro.dto.request.OtpDtos.ResendOtpRequest;
import in.postkaro.dto.request.OtpDtos.ResetPasswordRequest;
import in.postkaro.dto.request.OtpDtos.VerifyOtpRequest;
import in.postkaro.dto.request.RefreshRequest;
import in.postkaro.dto.request.SigninRequest;
import in.postkaro.dto.request.SignupRequest;
import in.postkaro.dto.response.ApiResponse;
import in.postkaro.dto.response.AuthResponse;
import in.postkaro.dto.response.UserResponse;
import in.postkaro.entity.EmailOtp;
import in.postkaro.entity.User;
import in.postkaro.repository.UserRepository;
import in.postkaro.service.AuthService;
import in.postkaro.service.OtpService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

	private final AuthService authService;
	private final UserRepository userRepository;
	private final OtpService otpService;
	// private final UserRepository userRepository;

	@PostMapping("/signup")
	public ResponseEntity<ApiResponse<AuthResponse>> signup(@Valid @RequestBody SignupRequest request) {
		AuthResponse data = authService.signup(request);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(data, "Account created successfully."));
	}

	@PostMapping("/signin")
	public ResponseEntity<ApiResponse<AuthResponse>> signin(@Valid @RequestBody SigninRequest request) {
		AuthResponse data = authService.signin(request);
		return ResponseEntity.ok(ApiResponse.ok(data, "Signed in successfully."));
	}

	@PostMapping("/refresh")
	public ResponseEntity<ApiResponse<AuthResponse>> refresh(@Valid @RequestBody RefreshRequest request) {
		AuthResponse data = authService.refresh(request);
		return ResponseEntity.ok(ApiResponse.ok(data, "Token refreshed."));
	}

	@PostMapping("/signout")
	public ResponseEntity<ApiResponse<Void>> signout(@AuthenticationPrincipal User user) {
		authService.signout(user);
		return ResponseEntity.ok(ApiResponse.ok(null, "Signed out."));
	}

	@GetMapping("/me")
	public ResponseEntity<ApiResponse<UserResponse>> me(@AuthenticationPrincipal User user) {
		User freshUser = userRepository.findByIdWithAccounts(user.getId()).orElseThrow();
		return ResponseEntity.ok(ApiResponse.ok(UserResponse.from(freshUser), "OK"));
	}

	@PostMapping("/logout")
	public ResponseEntity<Void> logout(@RequestBody LogoutRequest request) {
		authService.logout(request.getRefreshToken());
		return ResponseEntity.noContent().build();
	}

	/** Verify the 6-digit signup code. */
	@PostMapping("/verify-otp")
	public ResponseEntity<ApiResponse<UserResponse>> verifyOtp(@Valid @RequestBody VerifyOtpRequest req) {
		User user = otpService.verify(req.email(), EmailOtp.Purpose.SIGNUP, req.code());
		return ResponseEntity.ok(ApiResponse.ok(UserResponse.from(user), "Email verified."));
	}

	/**
	 * Send a fresh code. Always reports success, so it can't be used to probe for
	 * accounts.
	 */
	@PostMapping("/resend-otp")
	public ResponseEntity<ApiResponse<Void>> resendOtp(@Valid @RequestBody ResendOtpRequest req) {
		userRepository.findByEmail(req.email().toLowerCase().trim())
				.ifPresent(u -> otpService.send(u, EmailOtp.Purpose.SIGNUP));
		return ResponseEntity.ok(ApiResponse.ok(null, "If that account exists, a new code is on its way."));
	}

	/** Sends a password reset code. Always reports success. */
	@PostMapping("/forgot-password")
	public ResponseEntity<ApiResponse<Void>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest req) {
		userRepository.findByEmail(req.email().toLowerCase().trim())
				.ifPresent(u -> otpService.send(u, EmailOtp.Purpose.PASSWORD_RESET));
		return ResponseEntity.ok(ApiResponse.ok(null, "If that account exists, a reset code is on its way."));
	}

	/** Sets a new password using the code. */
	@PostMapping("/reset-password")
	public ResponseEntity<ApiResponse<Void>> resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
		authService.resetPassword(req.email(), req.code(), req.newPassword());
		return ResponseEntity.ok(ApiResponse.ok(null, "Password updated. Sign in with your new password."));
	}

}
