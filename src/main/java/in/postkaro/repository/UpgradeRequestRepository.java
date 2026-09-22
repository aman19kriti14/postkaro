package in.postkaro.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import in.postkaro.entity.UpgradeRequest;

@Repository
public interface UpgradeRequestRepository extends JpaRepository<UpgradeRequest, UUID> {

	List<UpgradeRequest> findByStatusOrderByCreatedAtDesc(UpgradeRequest.Status status);

	/** Don't spam yourself if the user taps the button five times. */
	Optional<UpgradeRequest> findFirstByUserIdAndStatusOrderByCreatedAtDesc(UUID userId, UpgradeRequest.Status status);

	List<UpgradeRequest> findByUserIdOrderByCreatedAtDesc(UUID userId);
}