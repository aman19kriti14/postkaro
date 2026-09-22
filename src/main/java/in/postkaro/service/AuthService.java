package in.postkaro.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import in.postkaro.dto.request.RefreshRequest;
import in.postkaro.dto.request.SigninRequest;
import in.postkaro.dto.request.SignupRequest;
import in.postkaro.dto.response.AuthResponse;
import in.postkaro.dto.response.UserResponse;
import in.postkaro.entity.RefreshToken;
import in.postkaro.entity.User;
import in.postkaro.exception.DuplicateEmailException;
import in.postkaro.exception.InvalidTokenException;
import in.postkaro.repository.RefreshTokenRepository;
import in.postkaro.repository.UserRepository;
import in.postkaro.security.JwtService;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthService {

	private final UserRepository userRepository;
	private final RefreshTokenRepository refreshTokenRepository;
	private final PasswordEncoder passwordEncoder;
	private final JwtService jwtService;
	private final CreditService creditService;

	@Transactional
	public AuthResponse signup(SignupRequest request) {
		if (userRepository.existsByEmail(request.getEmail().toLowerCase().trim())) {
			throw new DuplicateEmailException();
		}

		User user = User.builder().fullName(request.getFullName().trim()).email(request.getEmail().toLowerCase().trim())
				.password(passwordEncoder.encode(request.getPassword())).build();

		user = userRepository.save(user);

		// Start the trial only after the user row is committed; CreditService uses its
		// own transaction and would otherwise wait on this uncommitted row.
		UUID newUserId = user.getId();
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				creditService.getOrCreate(newUserId);
			}
		});

		return buildAuthResponse(user);
	}

	@Transactional
	public AuthResponse signin(SigninRequest request) {
		User user = userRepository.findByEmail(request.getEmail().toLowerCase().trim())
				.orElseThrow(() -> new BadCredentialsException("Invalid email or password."));

		if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
			throw new BadCredentialsException("Invalid email or password.");
		}

		return buildAuthResponse(user);
	}

	@Transactional
	public AuthResponse refresh(RefreshRequest request) {
		RefreshToken stored = refreshTokenRepository.findByTokenAndRevokedFalse(request.getRefreshToken())
				.orElseThrow(() -> new InvalidTokenException("Refresh token is invalid or revoked."));

		if (stored.getExpiresAt().isBefore(Instant.now())) {
			stored.setRevoked(true);
			refreshTokenRepository.save(stored);
			throw new InvalidTokenException("Refresh token has expired.");
		}

		// Rotate: revoke old, issue new
		stored.setRevoked(true);
		refreshTokenRepository.save(stored);

		User user = stored.getUser();
		return buildAuthResponse(user);
	}

	@Transactional
	public void signout(User user) {
		refreshTokenRepository.revokeAllByUserId(user.getId());
	}

	private AuthResponse buildAuthResponse(User user) {
		String accessToken = jwtService.generateAccessToken(user.getId(), user.getEmail());
		String refreshTokenValue = jwtService.generateRefreshTokenValue();

		RefreshToken refreshToken = RefreshToken.builder().token(refreshTokenValue).user(user)
				.expiresAt(Instant.now().plusMillis(jwtService.getRefreshTokenExpiry())).createdAt(Instant.now())
				.build();
		refreshTokenRepository.save(refreshToken);

		return AuthResponse.builder().user(UserResponse.from(user))
				.tokens(AuthResponse.TokenResponse.builder().accessToken(accessToken).refreshToken(refreshTokenValue)
						.expiresIn(jwtService.getAccessTokenExpiry()).build())
				.build();
	}

	@Transactional
	public void logout(String refreshToken) {
		if (refreshToken == null || refreshToken.isBlank()) {
			return;
		}
		refreshTokenRepository.findByTokenAndRevokedFalse(refreshToken).ifPresent(stored -> {
			stored.setRevoked(true);
			refreshTokenRepository.save(stored);
		});
	}
}