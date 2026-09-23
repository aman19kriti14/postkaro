package in.postkaro.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import in.postkaro.entity.EmailOtp;
import in.postkaro.entity.User;
import in.postkaro.repository.EmailOtpRepository;
import in.postkaro.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class OtpService {

	private static final Duration VALID_FOR = Duration.ofMinutes(10);
	private static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);
	private static final int MAX_ATTEMPTS = 5;

	private final EmailOtpRepository otps;
	private final UserRepository users;
	private final PasswordEncoder passwordEncoder;
	private final NotificationService notifications;
	private final SecureRandom random = new SecureRandom();

	/** Thrown for every OTP failure; the message is safe to show the user. */
	public static class OtpException extends RuntimeException {
		public OtpException(String message) {
			super(message);
		}
	}

	/**
	 * Issues a fresh code, invalidates any earlier ones, and emails it. Silently
	 * does nothing if the user is already verified.
	 *
	 * REQUIRES_NEW because signup calls this from an afterCommit callback, where
	 * the surrounding transaction is already closed and can't run writes.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void send(User user, EmailOtp.Purpose purpose) {
		Instant now = Instant.now();

		if (purpose == EmailOtp.Purpose.SIGNUP && user.isEmailVerified()) {
			return;
		}

		// Rate limit: one code a minute
		Optional<EmailOtp> latest = otps
				.findFirstByUserIdAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(user.getId(), purpose);
		if (latest.isPresent() && latest.get().getCreatedAt() != null
				&& latest.get().getCreatedAt().isAfter(now.minus(RESEND_COOLDOWN))) {
			long wait = RESEND_COOLDOWN.getSeconds() - Duration.between(latest.get().getCreatedAt(), now).getSeconds();
			throw new OtpException("Please wait " + Math.max(1, wait) + " seconds before asking for a new code.");
		}

		otps.consumeAllFor(user.getId(), purpose, now);

		String code = String.format("%06d", random.nextInt(1_000_000));

		otps.save(EmailOtp.builder().user(user).purpose(purpose).codeHash(passwordEncoder.encode(code))
				.expiresAt(now.plus(VALID_FOR)).build());

		notifications.sendTo(user.getEmail(), subjectFor(purpose, code), bodyFor(purpose, user, code));
		log.info("Sent {} OTP to user {}", purpose, user.getId());
	}

	/** Checks the code. Marks the user verified on a successful SIGNUP check. */
	@Transactional
	public User verify(String email, EmailOtp.Purpose purpose, String code) {
		if (code == null || !code.trim().matches("\\d{6}")) {
			throw new OtpException("Enter the 6-digit code from your email.");
		}

		User user = users.findByEmail(email == null ? "" : email.toLowerCase().trim())
				.orElseThrow(() -> new OtpException("We couldn't find that account."));

		EmailOtp otp = otps.findFirstByUserIdAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(user.getId(), purpose)
				.orElseThrow(() -> new OtpException("No active code. Ask for a new one."));

		Instant now = Instant.now();

		if (!otp.isUsable(now)) {
			throw new OtpException(otp.getAttempts() >= MAX_ATTEMPTS ? "Too many wrong attempts. Ask for a new code."
					: "That code has expired. Ask for a new one.");
		}

		if (!passwordEncoder.matches(code.trim(), otp.getCodeHash())) {
			otp.setAttempts(otp.getAttempts() + 1);
			otps.save(otp);
			int left = MAX_ATTEMPTS - otp.getAttempts();
			throw new OtpException(
					left > 0 ? "That code isn't right. " + left + " attempt" + (left == 1 ? "" : "s") + " left."
							: "Too many wrong attempts. Ask for a new code.");
		}

		otp.setConsumedAt(now);
		otps.save(otp);

		if (purpose == EmailOtp.Purpose.SIGNUP && !user.isEmailVerified()) {
			user.setEmailVerified(true);
			users.save(user);
			log.info("User {} verified their email", user.getId());
		}

		return user;
	}

	/** Clears out expired codes once a day. */
	@Scheduled(cron = "0 30 3 * * *", zone = "Asia/Kolkata")
	@Transactional
	public void purgeExpired() {
		int removed = otps.deleteExpired(Instant.now().minus(Duration.ofDays(1)));
		if (removed > 0) {
			log.info("Removed {} expired OTP rows", removed);
		}
	}

	// ---------------------------------------------------------------------

	private String subjectFor(EmailOtp.Purpose purpose, String code) {
		return purpose == EmailOtp.Purpose.SIGNUP ? code + " is your PostKaro verification code"
				: code + " is your PostKaro password reset code";
	}

	private String bodyFor(EmailOtp.Purpose purpose, User user, String code) {
		String action = purpose == EmailOtp.Purpose.SIGNUP ? "verify your email" : "reset your password";

		return "Hi " + user.getFullName().split(" ")[0] + ",\n\n" + "Your code is " + code + "\n\n"
				+ "Enter it in PostKaro to " + action + ". It expires in 10 minutes.\n\n"
				+ "If this wasn't you, you can ignore this email.\n\n" + "— PostKaro\n";
	}
}