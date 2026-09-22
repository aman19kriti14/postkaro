package in.postkaro.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "subscriptions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Subscription {

	public enum Status {
		TRIALING, ACTIVE, EXPIRED, CANCELLED
	}

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@OneToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id", nullable = false, unique = true)
	private User user;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private PlanTier plan;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Status status;

	/** Set only for trial users. */
	private Instant trialEndsAt;

	/** Current billing window. For a trial, end = trialEndsAt. */
	@Column(nullable = false)
	private Instant currentPeriodStart;

	@Column(nullable = false)
	private Instant currentPeriodEnd;

	/** Resets every period, unused credits are lost. */
	@Builder.Default
	@Column(nullable = false)
	private int monthlyCredits = 0;

	/** Purchased top-ups, never expire. */
	@Builder.Default
	@Column(nullable = false)
	private int topupCredits = 0;

	/** Extra connected accounts bought on top of the plan limit. */
	@Builder.Default
	@Column(nullable = false)
	private int extraAccounts = 0;

	@CreationTimestamp
	private Instant createdAt;

	@UpdateTimestamp
	private Instant updatedAt;
	/**
	 * Set once the "trial ending soon" email has gone out, so it's sent only once.
	 */
	private Instant trialWarningSentAt;

	public int totalCredits() {
		return monthlyCredits + topupCredits;
	}

	public int accountLimit() {
		return plan.getAccountLimit() + extraAccounts;
	}

	/** True while the user may create, generate and publish. */
	public boolean hasAccess(Instant now) {
		return switch (status) {
		case TRIALING -> trialEndsAt != null && now.isBefore(trialEndsAt);
		case ACTIVE -> now.isBefore(currentPeriodEnd);
		default -> false;
		};
	}
}