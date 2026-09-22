package in.postkaro.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

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

/**
 * One row per user: what PostKaro learned by reading the user's website and
 * connected accounts. BrandSettings is what the user chose; this is what we
 * found. We pre-fill BrandSettings from this, never the other way round.
 */
@Entity
@Table(name = "brand_profiles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BrandProfile {

	public enum Status {
		IDLE, RUNNING, READY, FAILED
	}

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@OneToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id", nullable = false, unique = true)
	private User user;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	@Builder.Default
	private Status status = Status.IDLE;

	// shown under the progress spinner / on failure ("Reading your website…")
	@Column(length = 500)
	private String statusMessage;

	// ---------- what we read ----------

	@Column(length = 500)
	private String websiteUrl;

	@Column(nullable = false)
	@Builder.Default
	private int websitePagesRead = 0;

	@Column(nullable = false)
	@Builder.Default
	private int postsRead = 0;

	// cleaned website text, kept so we can re-analyse without re-scraping
	@Column(columnDefinition = "TEXT")
	private String websiteText;

	// ---------- what we found ----------

	// full analysis as JSON (summary, pillars, voice, starter prompts, …)
	@Column(columnDefinition = "TEXT")
	private String analysisJson;

	private Instant analyzedAt;

	@CreationTimestamp
	private Instant createdAt;

	@UpdateTimestamp
	private Instant updatedAt;
}
