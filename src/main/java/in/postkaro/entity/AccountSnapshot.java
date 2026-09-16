package in.postkaro.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One row per connected account per IST day — used for "followers gained". */
@Entity
@Table(name = "account_snapshots", uniqueConstraints = @UniqueConstraint(columnNames = { "connected_account_id",
		"snapshot_date" }), indexes = @Index(name = "idx_snapshot_user_date", columnList = "user_id, snapshot_date"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccountSnapshot {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Column(name = "connected_account_id", nullable = false)
	private UUID connectedAccountId;

	@Column(nullable = false, length = 30)
	private String channel; // instagram

	@Column(name = "snapshot_date", nullable = false)
	private LocalDate snapshotDate; // IST date

	@Column(nullable = false)
	private long followers;

	@CreationTimestamp
	private Instant createdAt;
}