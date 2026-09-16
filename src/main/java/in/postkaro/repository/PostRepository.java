package in.postkaro.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import in.postkaro.entity.Post;
import in.postkaro.enums.PostStatus;

public interface PostRepository extends JpaRepository<Post, UUID> {

	@Query("SELECT p FROM Post p LEFT JOIN FETCH p.media LEFT JOIN FETCH p.channels WHERE p.id = :id AND p.user.id = :userId")
	Optional<Post> findByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

	@EntityGraph(attributePaths = { "media", "channels" })
	List<Post> findByUserIdAndStatusOrderByCreatedAtDesc(UUID userId, PostStatus status);

	@EntityGraph(attributePaths = { "media", "channels" })
	List<Post> findByUserIdOrderByCreatedAtDesc(UUID userId);

	@EntityGraph(attributePaths = { "media", "channels" })
	List<Post> findByUserIdAndStatus(UUID userId, PostStatus status);

	@EntityGraph(attributePaths = { "media", "campaign", "channels" })
	List<Post> findByUserIdAndCampaignIsNotNull(UUID userId);

	@EntityGraph(attributePaths = { "media", "channels" })
	List<Post> findByCampaignIdAndUserIdOrderByScheduledAtAsc(UUID campaignId, UUID userId);

	// Posts from unfinished campaigns stay off the calendar
	@EntityGraph(attributePaths = { "media", "campaign", "channels" })
	@Query("""
			select distinct p from Post p
			where p.user.id = :userId
			  and (p.campaign is null or p.campaign.status <> in.postkaro.enums.CampaignStatus.DRAFT)
			  and (
			        (p.status = in.postkaro.enums.PostStatus.PUBLISHED
			            and p.publishedAt >= :from and p.publishedAt < :to)
			     or (p.status <> in.postkaro.enums.PostStatus.PUBLISHED
			            and p.scheduledAt >= :from and p.scheduledAt < :to)
			  )
			""")
	List<Post> findForCalendar(@Param("userId") UUID userId, @Param("from") Instant from, @Param("to") Instant to);

	long countByUserIdAndStatus(UUID userId, PostStatus status);

	@EntityGraph(attributePaths = { "media", "channels" })
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

	// [campaignId, status, count] for every campaign post of this user
	@Query("""
			select p.campaign.id, p.status, count(p) from Post p
			where p.user.id = :userId and p.campaign is not null
			group by p.campaign.id, p.status
			""")
	List<Object[]> countByCampaignAndStatus(@Param("userId") UUID userId);

	// Due posts; campaign posts only if auto-publish is on
	@Query("""
			select p.id from Post p
			where p.status = in.postkaro.enums.PostStatus.SCHEDULED
			  and p.scheduledAt <= :now
			  and (p.campaign is null or p.campaign.autoPublish = true)
			order by p.scheduledAt
			""")
	List<UUID> findDueIds(@Param("now") Instant now);

	// Marks a post as PUBLISHING only if it's still SCHEDULED, so it can't be sent
	// twice
	@Modifying
	@Query("""
			update Post p set p.status = in.postkaro.enums.PostStatus.PUBLISHING
			where p.id = :id and p.status = in.postkaro.enums.PostStatus.SCHEDULED
			""")
	int claim(@Param("id") UUID id);
}