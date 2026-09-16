package in.postkaro.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import in.postkaro.entity.Campaign;

public interface CampaignRepository extends JpaRepository<Campaign, UUID> {
	List<Campaign> findByUserIdOrderByStartsOnDesc(UUID userId);

	Optional<Campaign> findByIdAndUserId(UUID id, UUID userId);
}