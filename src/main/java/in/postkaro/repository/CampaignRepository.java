package in.postkaro.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
			order by c.startsOn
			""")
	List<Campaign> findOverlapping(@Param("userId") UUID userId, @Param("from") LocalDate from,
			@Param("to") LocalDate to);

	long countByUserIdAndEndsOnGreaterThanEqual(UUID userId, LocalDate date);
}