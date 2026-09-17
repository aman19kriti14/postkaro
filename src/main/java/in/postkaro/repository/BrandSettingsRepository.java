package in.postkaro.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import in.postkaro.entity.BrandSettings;

public interface BrandSettingsRepository extends JpaRepository<BrandSettings, UUID> {

	Optional<BrandSettings> findByUserId(UUID userId);
}