package in.postkaro.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import in.postkaro.enums.PlanTier;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "upgrade_requests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpgradeRequest {

	public enum Kind {
		PLAN, TOPUP
	}

	public enum Status {
		PENDING, DONE, CANCELLED
	}

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private Kind kind;

	/** Set when kind = PLAN. */
	@Enumerated(EnumType.STRING)
	@Column(length = 20)
	private PlanTier plan;

	/** Set when kind = TOPUP. */
	@Builder.Default
	@Column(nullable = false)
	private int credits = 0;

	/** Whatever the user typed in the request box. */
	@Column(length = 500)
	private String message;

	/** Phone from their profile, so you can call them back. */
	@Column(length = 20)
	private String phone;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private Status status;

	private Instant handledAt;

	@CreationTimestamp
	private Instant createdAt;
}