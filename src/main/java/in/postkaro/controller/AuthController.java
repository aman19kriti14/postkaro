package in.postkaro.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import in.postkaro.dto.request.RefreshRequest;
import in.postkaro.dto.request.SigninRequest;
import in.postkaro.dto.request.SignupRequest;
import in.postkaro.dto.response.ApiResponse;
import in.postkaro.dto.response.AuthResponse;
import in.postkaro.dto.response.UserResponse;
import in.postkaro.entity.User;
import in.postkaro.repository.UserRepository;
import in.postkaro.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

	private final AuthService authService;
	private final UserRepository userRepository;

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
}
