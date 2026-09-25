package in.postkaro.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import in.postkaro.entity.Campaign;

public interface CampaignRepository extends JpaRepository<Campaign, UUID> {

	List<Campaign> findByUserIdOrderByStartsOnDesc(UUID userId);

	Optional<Campaign> findByIdAndUserId(UUID id, UUID userId);

	@Query("""
			select c from Campaign c
			where c.user.id = :userId
			  and c.startsOn <= :to
			  and c.endsOn >= :from
			  and c.status <> in.postkaro.enums.CampaignStatus.STOPPED
			order by c.startsOn
			""")
	List<Campaign> findOverlapping(@Param("userId") UUID userId, @Param("from") LocalDate from,
			@Param("to") LocalDate to);

	long countByUserIdAndEndsOnGreaterThanEqual(UUID userId, LocalDate date);

	// Sidebar badge: live campaigns, stopped ones left out
	long countByUserIdAndEndsOnGreaterThanEqualAndStatusNot(UUID userId, LocalDate date,
			in.postkaro.enums.CampaignStatus status);

	List<Campaign> findByUserIdOrderByCreatedAtDesc(UUID userId);

	// Dashboard "Active campaigns": live first, then upcoming, then drafts.
	// Finished or archived campaigns are left out.
	@Query("""
			select c from Campaign c
			where c.user.id = :userId
			  and c.status in (in.postkaro.enums.CampaignStatus.DRAFT,
			                   in.postkaro.enums.CampaignStatus.SCHEDULED)
			  and (c.endsOn is null or c.endsOn >= :today)
			order by
			  case
			    when c.status = in.postkaro.enums.CampaignStatus.SCHEDULED
			         and c.startsOn <= :today then 0
			    when c.status = in.postkaro.enums.CampaignStatus.SCHEDULED then 1
			    else 2
			  end,
			  c.startsOn asc nulls last,
			  c.updatedAt desc
			""")
	List<Campaign> dashActive(@Param("userId") UUID userId, @Param("today") LocalDate today, Pageable page);
}