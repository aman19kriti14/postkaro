package in.postkaro.repository;

import in.postkaro.entity.ConnectedAccount;
import in.postkaro.enums.SocialPlatform;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ConnectedAccountRepository extends JpaRepository<ConnectedAccount, UUID> {
	boolean existsByUserIdAndPlatformAndPlatformUserId(UUID userId, SocialPlatform platform, String platformUserId);
}