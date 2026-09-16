package in.postkaro.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import in.postkaro.entity.ConnectedAccount;
import in.postkaro.enums.SocialPlatform;

public interface ConnectedAccountRepository extends JpaRepository<ConnectedAccount, UUID> {
	boolean existsByUserIdAndPlatformAndPlatformUserId(UUID userId, SocialPlatform platform, String platformUserId);
	List<ConnectedAccount> findByUserIdAndPlatform(UUID userId, SocialPlatform platform);
	Optional<ConnectedAccount> findByUserIdAndPlatformAndPlatformUserId(
	        UUID userId,
	        SocialPlatform platform,
	        String platformUserId
	);
	
	// Follower snapshots: [accountId, userId, platformUserId, accessToken] for active Instagram accounts
	@Query("""
			select a.id, a.user.id, a.platformUserId, a.accessToken
			from ConnectedAccount a
			where a.platform = in.postkaro.enums.SocialPlatform.INSTAGRAM
			  and a.active = true
			""")
	List<Object[]> activeInstagramForSnapshot();
}