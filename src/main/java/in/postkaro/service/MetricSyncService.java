package in.postkaro.service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;

import in.postkaro.entity.ConnectedAccount;
import in.postkaro.entity.PostMetric;
import in.postkaro.enums.SocialPlatform;
import in.postkaro.repository.ConnectedAccountRepository;
import in.postkaro.repository.PostMetricRepository;
import in.postkaro.repository.PostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class MetricSyncService {

	private static final String IG_GRAPH = "https://graph.instagram.com/v21.0";
	private static final int BATCH = 50;

	private final PostMetricRepository metrics;
	private final PostRepository postRepository;
	private final ConnectedAccountRepository connectedAccounts;

	private final RestClient http = RestClient.create();

	// ---------- called by PublishService after a successful publish ----------

	@Transactional
	public void recordPublished(UUID postId, UUID userId, String channel, String externalId) {
		PostMetric m = metrics.findByPostIdAndChannel(postId, channel).orElseGet(() -> PostMetric.builder()
				.post(postRepository.getReferenceById(postId)).userId(userId).channel(channel).build());
		m.setExternalId(externalId);
		m.setPublishedAt(Instant.now());
		metrics.save(m);
	}

	// ---------- scheduled sync: every 3 hours, posts from last 30 days ----------

	@Scheduled(fixedDelay = 3 * 60 * 60 * 1000L, initialDelay = 60_000L)
	public void syncDue() {
		Instant now = Instant.now();
		List<PostMetric> due = metrics.findDueForSync(now.minus(Duration.ofDays(30)), now.minus(Duration.ofHours(3)),
				PageRequest.of(0, BATCH));

		if (due.isEmpty())
			return;
		log.info("Metric sync: {} rows due", due.size());

		for (PostMetric m : due) {
			try {
				syncOne(m);
			} catch (Exception e) {
				log.warn("Metric sync failed for {} ({}): {}", m.getId(), m.getChannel(), e.getMessage());
				m.setSyncError(limit(e.getMessage()));
				m.setLastSyncedAt(now); // retry next cycle, not every minute
				metrics.save(m);
			}
		}
	}

	private void syncOne(PostMetric m) {
		if (!"instagram".equalsIgnoreCase(m.getChannel())) {
			return; // Facebook insights come later
		}

		String token = instagramToken(m.getUserId());
		if (token == null) {
			throw new IllegalStateException("No active Instagram connection");
		}

		Map<String, Long> v;
		try {
			v = fetchInsights(m.getExternalId(), token);
		} catch (HttpClientErrorException.BadRequest e) {
			// stories / some media types reject certain metrics
			v = fetchBasicCounts(m.getExternalId(), token);
		}

		m.setReach(v.getOrDefault("reach", m.getReach()));
		m.setViews(v.getOrDefault("views", m.getViews()));
		m.setLikes(v.getOrDefault("likes", m.getLikes()));
		m.setComments(v.getOrDefault("comments", m.getComments()));
		m.setShares(v.getOrDefault("shares", m.getShares()));
		m.setSaves(v.getOrDefault("saved", m.getSaves()));
		m.setSyncError(null);
		m.setLastSyncedAt(Instant.now());
		metrics.save(m);
	}

	// ---------- Instagram API ----------

	private Map<String, Long> fetchInsights(String mediaId, String token) {
		JsonNode res = http.get()
				.uri(IG_GRAPH + "/{id}/insights?metric=reach,likes,comments,shares,saved,views&access_token={t}",
						mediaId, token)
				.retrieve().body(JsonNode.class);

		Map<String, Long> out = new HashMap<>();
		if (res != null) {
			for (JsonNode item : res.path("data")) {
				out.put(item.path("name").asText(), item.path("values").path(0).path("value").asLong(0));
			}
		}
		return out;
	}

	private Map<String, Long> fetchBasicCounts(String mediaId, String token) {
		JsonNode res = http.get()
				.uri(IG_GRAPH + "/{id}?fields=like_count,comments_count&access_token={t}", mediaId, token).retrieve()
				.body(JsonNode.class);

		if (res == null)
			return Map.of();
		return Map.of("likes", res.path("like_count").asLong(0), "comments", res.path("comments_count").asLong(0));
	}

	// ---------- helpers ----------

	private String instagramToken(UUID userId) {
		return connectedAccounts.findByUserIdAndPlatform(userId, SocialPlatform.INSTAGRAM).stream()
				.filter(ConnectedAccount::isActive).map(ConnectedAccount::getAccessToken)
				.filter(t -> t != null && !t.isBlank()).findFirst().orElse(null);
	}

	private static String limit(String s) {
		if (s == null)
			return null;
		return s.length() > 300 ? s.substring(0, 300) : s;
	}
}