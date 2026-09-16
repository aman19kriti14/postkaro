package in.postkaro.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import in.postkaro.entity.Post;
import in.postkaro.enums.PostStatus;

public interface PostRepository extends JpaRepository<Post, UUID> {

	@Query("SELECT p FROM Post p LEFT JOIN FETCH p.media LEFT JOIN FETCH p.channels WHERE p.id = :id AND p.user.id = :userId")
	Optional<Post> findByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

	List<Post> findByUserIdAndStatusOrderByCreatedAtDesc(UUID userId, PostStatus status);

	List<Post> findByUserIdOrderByCreatedAtDesc(UUID userId);

	@EntityGraph(attributePaths = { "media" })
	List<Post> findByUserIdAndStatus(UUID userId, PostStatus status);

	@EntityGraph(attributePaths = { "media", "campaign" })
	List<Post> findByUserIdAndCampaignIsNotNull(UUID userId);

	@EntityGraph(attributePaths = { "media" })
	List<Post> findByCampaignIdAndUserIdOrderByScheduledAtAsc(UUID campaignId, UUID userId);

	@EntityGraph(attributePaths = { "media", "campaign" })
	@Query("""
			select distinct p from Post p
			where p.user.id = :userId
			  and (
			        (p.status = in.postkaro.enums.PostStatus.PUBLISHED
			            and p.publishedAt >= :from and p.publishedAt < :to)
			     or (p.status <> in.postkaro.enums.PostStatus.PUBLISHED
			            and p.scheduledAt >= :from and p.scheduledAt < :to)
			  )
			""")
	List<Post> findForCalendar(@Param("userId") UUID userId, @Param("from") Instant from, @Param("to") Instant to);

	long countByUserIdAndStatus(UUID userId, PostStatus status);

	@EntityGraph(attributePaths = { "media" })
	List<Post> findByCampaignIdOrderByScheduledAtAsc(UUID campaignId);

	void deleteByCampaignId(UUID campaignId);

	// Something else already going out within the window (for the clash check)
	@Query("""
			select count(p) > 0 from Post p
			where p.user.id = :userId
			  and (p.campaign is null or p.campaign.id <> :campaignId)
			  and p.status in (in.postkaro.enums.PostStatus.SCHEDULED, in.postkaro.enums.PostStatus.NEEDS_REVIEW)
			  and p.scheduledAt > :from and p.scheduledAt < :to
			""")
	boolean existsClash(@Param("userId") UUID userId, @Param("campaignId") UUID campaignId, @Param("from") Instant from,
			@Param("to") Instant to);
}