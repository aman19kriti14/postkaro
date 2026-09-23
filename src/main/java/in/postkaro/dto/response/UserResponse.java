package in.postkaro.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import in.postkaro.entity.ConnectedAccount;
import in.postkaro.entity.User;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UserResponse {
	private UUID id;
	private String fullName;
	private String email;
	private String avatarUrl;
	private boolean onboardingComplete;
	private List<ConnectedAccountResponse> connectedAccounts;
	private Instant createdAt;
	private boolean emailVerified;

	public static UserResponse from(User user) {
		return UserResponse.builder().id(user.getId()).fullName(user.getFullName()).email(user.getEmail())
				.avatarUrl(user.getAvatarUrl()).emailVerified(user.isEmailVerified())
				.onboardingComplete(user.isOnboardingComplete())
				.connectedAccounts(user.getConnectedAccounts().stream().map(ConnectedAccountResponse::from).toList())
				.createdAt(user.getCreatedAt()).build();
	}

	@Data
	@Builder
	public static class ConnectedAccountResponse {
		private UUID id;
		private String platform;
		private String platformUserId;
		private String platformUsername;
		private String platformDisplayName;
		private String avatarUrl;
		private Instant accessTokenExpiresAt;
		private Instant connectedAt;
		private boolean active;

		public static ConnectedAccountResponse from(ConnectedAccount ca) {
			return ConnectedAccountResponse.builder().id(ca.getId()).platform(ca.getPlatform().name().toLowerCase())
					.platformUserId(ca.getPlatformUserId()).platformUsername(ca.getPlatformUsername())
					.platformDisplayName(ca.getPlatformDisplayName()).avatarUrl(ca.getAvatarUrl())
					.accessTokenExpiresAt(ca.getAccessTokenExpiresAt()).connectedAt(ca.getConnectedAt())
					.active(ca.isActive()).build();
		}
	}
}
