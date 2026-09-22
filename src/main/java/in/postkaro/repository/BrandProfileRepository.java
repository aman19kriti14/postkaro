package in.postkaro.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import in.postkaro.entity.BrandProfile;

public interface BrandProfileRepository extends JpaRepository<BrandProfile, UUID> {

	Optional<BrandProfile> findByUserId(UUID userId);
}
