package in.postkaro.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "email_otps", indexes = {
		@Index(name = "idx_otp_user_purpose", columnList = "user_id, purpose, consumed_at") })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailOtp {

	public enum Purpose {
		SIGNUP, PASSWORD_RESET
	}

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Purpose purpose;

	/** BCrypt hash of the 6-digit code — never store the code itself. */
	@Column(nullable = false, length = 100)
	private String codeHash;

	@Column(nullable = false)
	private Instant expiresAt;

	/** Wrong guesses so far. Locked after 5. */
	@Builder.Default
	@Column(nullable = false)
	private int attempts = 0;

	/** Set once used, so a code can't be replayed. */
	@Column(name = "consumed_at")
	private Instant consumedAt;

	@CreationTimestamp
	private Instant createdAt;

	public boolean isUsable(Instant now) {
		return consumedAt == null && attempts < 5 && now.isBefore(expiresAt);
	}
}