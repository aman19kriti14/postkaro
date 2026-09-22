package in.postkaro.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class AdminInsightsDtos {

	/**
	 * One person you might want to call. `detail` says why they're on this list.
	 */
	public record Lead(UUID userId, String name, String email, String phone, Instant signedUpAt, String detail) {
	}

	public record PendingRequest(UUID requestId, String name, String email, String phone, String kind, String plan,
			int credits, String message, Instant requestedAt) {
	}

	public record Insights(int totalUsers, List<Lead> noAccountConnected, List<Lead> connectedButNoPost,
			List<Lead> postedButNothingPublished, List<Lead> trialEndingSoon, List<PendingRequest> pendingRequests) {
	}
}