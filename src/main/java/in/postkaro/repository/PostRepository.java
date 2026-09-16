package in.postkaro.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
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
	// Posts from unfinished campaigns stay off the calendar
	@EntityGraph(attributePaths = { "media", "campaign", "channels" })
	@Query("""
			select distinct p from Post p
			left join p.campaign c
			where p.user.id = :userId
			  and (c is null or c.status <> in.postkaro.enums.CampaignStatus.DRAFT)
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
	// Something else already going out within the window (for the clash check)
	@Query("""
			select count(p) > 0 from Post p
			left join p.campaign c
			where p.user.id = :userId
			  and (c is null or c.id <> :campaignId)
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
	// Due posts; campaign posts only if auto-publish is on
	@Query("""
			select p.id from Post p
			left join p.campaign c
			where p.status = in.postkaro.enums.PostStatus.SCHEDULED
			  and p.scheduledAt <= :now
			  and (c is null or c.autoPublish = true)
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

	// Everything publishing needs, in one query
	@Query("""
			select distinct p from Post p
			left join fetch p.media
			left join fetch p.channels
			left join fetch p.publishedChannels
			where p.id = :id
			""")
	Optional<Post> findForPublish(@Param("id") UUID id);

	// Scheduler: take a due post only if it's still SCHEDULED
	@Modifying
	@Query("""
			update Post p set p.status = in.postkaro.enums.PostStatus.PUBLISHING, p.updatedAt = :now
			where p.id = :id and p.status = in.postkaro.enums.PostStatus.SCHEDULED
			""")
	int claim(@Param("id") UUID id, @Param("now") Instant now);

	// Publish now: take the post if it isn't already out or in progress
	@Modifying
	@Query("""
			update Post p set p.status = in.postkaro.enums.PostStatus.PUBLISHING, p.updatedAt = :now
			where p.id = :id and p.user.id = :userId
			  and p.status in (in.postkaro.enums.PostStatus.DRAFT,
			                   in.postkaro.enums.PostStatus.SCHEDULED,
			                   in.postkaro.enums.PostStatus.FAILED)
			""")
	int claimForPublish(@Param("id") UUID id, @Param("userId") UUID userId, @Param("now") Instant now);

	// Posts stuck in PUBLISHING (e.g. the server restarted mid-publish)
	@Modifying
	@Query("""
			update Post p set p.status = in.postkaro.enums.PostStatus.FAILED,
			                  p.publishError = 'Publishing timed out. Try again.'
			where p.status = in.postkaro.enums.PostStatus.PUBLISHING and p.updatedAt < :cutoff
			""")
	int failStuck(@Param("cutoff") Instant cutoff);
	// ---------- dashboard ----------

	// "Scheduled · next 7 days" card (drafts inside unfinished campaigns don't
	// count)
	@Query("""
			select count(p) from Post p
			left join p.campaign c
			where p.user.id = :userId
			  and p.status in (in.postkaro.enums.PostStatus.SCHEDULED, in.postkaro.enums.PostStatus.NEEDS_REVIEW)
			  and (c is null or c.status <> in.postkaro.enums.CampaignStatus.DRAFT)
			  and p.scheduledAt >= :from and p.scheduledAt < :to
			""")
	long dashCountUpcoming(@Param("userId") UUID userId, @Param("from") Instant from, @Param("to") Instant to);

	// "Published" card, this month vs last month
	@Query("""
			select count(p) from Post p
			where p.user.id = :userId
			  and p.status = in.postkaro.enums.PostStatus.PUBLISHED
			  and p.publishedAt >= :from and p.publishedAt < :to
			""")
	long dashCountPublished(@Param("userId") UUID userId, @Param("from") Instant from, @Param("to") Instant to);

	// "Up next" list: anything with a future time that isn't out yet
	@Query("""
			select p from Post p
			left join p.campaign c
			where p.user.id = :userId
			  and p.status in (in.postkaro.enums.PostStatus.SCHEDULED,
			                   in.postkaro.enums.PostStatus.NEEDS_REVIEW,
			                   in.postkaro.enums.PostStatus.DRAFT)
			  and (c is null or c.status <> in.postkaro.enums.CampaignStatus.DRAFT)
			  and p.scheduledAt >= :now
			order by p.scheduledAt asc
			""")
	List<Post> dashUpNext(@Param("userId") UUID userId, @Param("now") Instant now, Pageable page);
}