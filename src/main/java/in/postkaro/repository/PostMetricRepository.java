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
}