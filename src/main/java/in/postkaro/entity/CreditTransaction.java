package in.postkaro.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import in.postkaro.enums.CreditAction;
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
@Table(name = "credit_transactions", indexes = {
		@Index(name = "idx_credit_tx_user_created", columnList = "user_id, created_at") })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreditTransaction {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private CreditAction action;

	/** Signed total: negative for spends, positive for grants/top-ups/refunds. */
	@Column(nullable = false)
	private int amount;

	/**
	 * How much of `amount` hit each bucket, so a refund goes back to the right one.
	 */
	@Column(nullable = false)
	private int monthlyDelta;

	@Column(nullable = false)
	private int topupDelta;

	/** Total balance (monthly + topup) right after this transaction. */
	@Column(nullable = false)
	private int balanceAfter;

	/** For a refund: the id of the spend it reverses. */
	private UUID refTransactionId;

	@Column(length = 255)
	private String note;

	@CreationTimestamp
	@Column(name = "created_at", updatable = false)
	private Instant createdAt;
}