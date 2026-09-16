package in.postkaro.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "post_metrics", uniqueConstraints = @UniqueConstraint(columnNames = { "post_id", "channel" }), indexes = {
		@Index(name = "idx_metric_user_published", columnList = "user_id, published_at"),
		@Index(name = "idx_metric_synced", columnList = "last_synced_at") })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PostMetric {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "post_id", nullable = false)
	private Post post;

	// denormalised so dashboard queries don't need to join posts
	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Column(nullable = false, length = 30)
	private String channel; // instagram, facebook, linkedin...

	@Column(name = "external_id", nullable = false, length = 100)
	private String externalId; // IG media id / FB post id returned on publish

	@Column(name = "published_at", nullable = false)
	private Instant publishedAt;

	@Builder.Default
	private long reach = 0;
	@Builder.Default
	private long impressions = 0;
	@Builder.Default
	private long likes = 0;
	@Builder.Default
	private long comments = 0;
	@Builder.Default
	private long shares = 0;
	@Builder.Default
	private long saves = 0;
	@Builder.Default
	private long views = 0; // reels / video plays

	@Column(name = "last_synced_at")
	private Instant lastSyncedAt; // null = never synced yet

	@Column(length = 300)
	private String syncError;

	@CreationTimestamp
	private Instant createdAt;

	// which connected account published this post; its token is used for insights
	@Column(name = "connected_account_id")
	private UUID connectedAccountId;

	// engagement % = interactions / reach
	@Transient
	public double getEngagementRate() {
		if (reach <= 0)
			return 0;
		return (likes + comments + shares + saves) * 100.0 / reach;
	}
}