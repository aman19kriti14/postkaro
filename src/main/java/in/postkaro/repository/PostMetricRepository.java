package in.postkaro.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import in.postkaro.entity.PostMetric;

public interface PostMetricRepository extends JpaRepository<PostMetric, UUID> {

	Optional<PostMetric> findByPostIdAndChannel(UUID postId, String channel);

	// ── Sync job ─────────────────────────────────────────────
	// Posts published after `since` that were never synced or are stale
	@Query("""
			SELECT m FROM PostMetric m
			WHERE m.publishedAt >= :since
			  AND (m.lastSyncedAt IS NULL OR m.lastSyncedAt < :staleBefore)
			ORDER BY m.lastSyncedAt ASC NULLS FIRST
			""")
	List<PostMetric> findDueForSync(@Param("since") Instant since, @Param("staleBefore") Instant staleBefore,
			Pageable page);

	// ── Dashboard stat cards ─────────────────────────────────
	// Returns [reach, interactions] for the window
	@Query("""
			SELECT COALESCE(SUM(m.reach), 0),
			       COALESCE(SUM(m.likes + m.comments + m.shares + m.saves), 0)
			FROM PostMetric m
			WHERE m.userId = :userId
			  AND m.publishedAt >= :from AND m.publishedAt < :to
			""")
	List<Object[]> sumReachAndInteractions(@Param("userId") UUID userId, @Param("from") Instant from,
			@Param("to") Instant to);

	// ── "What's working" bars (grouped by IST day) ───────────
	// Returns [date (java.sql.Date), reach (Number)]
	@Query(value = """
			SELECT CAST(published_at AT TIME ZONE 'Asia/Kolkata' AS DATE) AS day,
			       SUM(reach) AS reach
			FROM post_metrics
			WHERE user_id = :userId
			  AND published_at >= :from AND published_at < :to
			GROUP BY day
			ORDER BY day
			""", nativeQuery = true)
	List<Object[]> dailyReach(@Param("userId") UUID userId, @Param("from") Instant from, @Param("to") Instant to);

	// ── "What's working" top posts ───────────────────────────
	@Query("""
			SELECT m FROM PostMetric m
			JOIN FETCH m.post p
			WHERE m.userId = :userId
			  AND m.publishedAt >= :from
			  AND m.lastSyncedAt IS NOT NULL
			ORDER BY m.reach DESC
			""")
	List<PostMetric> topByReach(@Param("userId") UUID userId, @Param("from") Instant from, Pageable page);

	@Query("""
			SELECT m FROM PostMetric m
			JOIN FETCH m.post p
			WHERE m.userId = :userId
			  AND m.publishedAt >= :from
			  AND m.reach >= :minReach
			ORDER BY (m.likes + m.comments + m.shares + m.saves) * 1.0 / m.reach DESC
			""")
	List<PostMetric> topByEngagement(@Param("userId") UUID userId, @Param("from") Instant from,
			@Param("minReach") long minReach, Pageable page);

	// AI studio insights: [format, publishedAt, reach, likes, comments, shares,
	// saves]
	@Query("""
			SELECT p.format, m.publishedAt, m.reach, m.likes, m.comments, m.shares, m.saves
			FROM PostMetric m JOIN m.post p
			WHERE m.userId = :userId
			  AND m.channel = 'instagram'
			  AND m.lastSyncedAt IS NOT NULL
			  AND m.publishedAt >= :from
			""")
	List<Object[]> insightRows(@Param("userId") UUID userId, @Param("from") Instant from);

	// ---------- analytics ----------

	// Stat cards: [reach, likes, comments, shares, saves, postCount]
	@Query("""
			SELECT COALESCE(SUM(m.reach), 0),
			       COALESCE(SUM(m.likes), 0),
			       COALESCE(SUM(m.comments), 0),
			       COALESCE(SUM(m.shares), 0),
			       COALESCE(SUM(m.saves), 0),
			       COUNT(DISTINCT m.post.id)
			FROM PostMetric m
			WHERE m.userId = :userId
			  AND m.publishedAt >= :from AND m.publishedAt < :to
			  AND (:channel IS NULL OR m.channel = :channel)
			""")
	List<Object[]> analyticsTotals(@Param("userId") UUID userId, @Param("from") Instant from, @Param("to") Instant to,
			@Param("channel") String channel);

	// Chart buckets by IST day: [date, reach, interactions]
	@Query(value = """
			SELECT CAST(published_at AT TIME ZONE 'Asia/Kolkata' AS DATE) AS day,
			       COALESCE(SUM(reach), 0),
			       COALESCE(SUM(likes + comments + shares + saves), 0)
			FROM post_metrics
			WHERE user_id = :userId
			  AND published_at >= :from AND published_at < :to
			  AND (CAST(:channel AS VARCHAR) IS NULL OR channel = :channel)
			GROUP BY day
			ORDER BY day
			""", nativeQuery = true)
	List<Object[]> analyticsDaily(@Param("userId") UUID userId, @Param("from") Instant from, @Param("to") Instant to,
			@Param("channel") String channel);

	// Top posts, one row per post (channels merged): [postId, reach, interactions,
	// shares, saves, comments, publishedAt]
	@Query("""
			SELECT m.post.id,
			       SUM(m.reach),
			       SUM(m.likes + m.comments + m.shares + m.saves),
			       SUM(m.shares),
			       SUM(m.saves),
			       SUM(m.comments),
			       MIN(m.publishedAt)
			FROM PostMetric m
			WHERE m.userId = :userId
			  AND m.publishedAt >= :from AND m.publishedAt < :to
			  AND (:channel IS NULL OR m.channel = :channel)
			GROUP BY m.post.id
			HAVING SUM(m.reach) > 0
			ORDER BY SUM(m.reach) DESC
			""")
	List<Object[]> analyticsTopPosts(@Param("userId") UUID userId, @Param("from") Instant from, @Param("to") Instant to,
			@Param("channel") String channel, Pageable page);

	// By channel: [channel, postCount, reach, interactions, syncedCount]
	@Query("""
			SELECT m.channel,
			       COUNT(DISTINCT m.post.id),
			       COALESCE(SUM(m.reach), 0),
			       COALESCE(SUM(m.likes + m.comments + m.shares + m.saves), 0),
			       SUM(CASE WHEN m.lastSyncedAt IS NOT NULL AND m.syncError IS NULL THEN 1 ELSE 0 END)
			FROM PostMetric m
			WHERE m.userId = :userId
			  AND m.publishedAt >= :from AND m.publishedAt < :to
			GROUP BY m.channel
			""")
	List<Object[]> analyticsByChannel(@Param("userId") UUID userId, @Param("from") Instant from,
			@Param("to") Instant to);

	// "What to do more of" source rows: [format, publishedAt, reach, interactions,
	// channel]
	@Query("""
			SELECT p.format, m.publishedAt, m.reach,
			       m.likes + m.comments + m.shares + m.saves, m.channel
			FROM PostMetric m JOIN m.post p
			WHERE m.userId = :userId
			  AND m.publishedAt >= :from AND m.publishedAt < :to
			  AND m.lastSyncedAt IS NOT NULL
			  AND (:channel IS NULL OR m.channel = :channel)
			""")
	List<Object[]> analyticsRows(@Param("userId") UUID userId, @Param("from") Instant from, @Param("to") Instant to,
			@Param("channel") String channel);
}