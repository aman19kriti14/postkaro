package in.postkaro.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import in.postkaro.entity.ConnectedAccount;
import in.postkaro.entity.Post;
import in.postkaro.entity.PostMedia;
import in.postkaro.enums.PostStatus;
import in.postkaro.enums.SocialPlatform;
import in.postkaro.repository.ConnectedAccountRepository;
import in.postkaro.repository.PostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class PublishService {

	private static final String INSTAGRAM_API = "https://graph.instagram.com/v21.0";
	private static final String FACEBOOK_API = "https://graph.facebook.com/v21.0";
	private static final String MEDIA_PROXY = "https://postkaro-production.up.railway.app/api/v1/media/";

	private final PostRepository postRepository;
	private final ConnectedAccountRepository connectedAccountRepository;
	private final TransactionTemplate tx;
	private final MetricSyncService metricSyncService; // NEW

	private final RestClient http = RestClient.create();
	private final ObjectMapper json = new ObjectMapper();

	public record PublishResult(boolean success, List<String> published, String error) {
	}

	/** A failure with a message that's safe to show the user. */
	public static class PublishException extends RuntimeException {
		public PublishException(String message) {
			super(message);
		}
	}

	// ---------- entry points ----------

	/** "Publish now" from the app. */
	public PublishResult publishPost(UUID postId, UUID userId) {
		Integer claimed = tx.execute(s -> postRepository.claimForPublish(postId, userId, Instant.now()));
		if (claimed == null || claimed == 0) {
			Post post = postRepository.findByIdAndUserId(postId, userId)
					.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found"));
			String reason = switch (post.getStatus()) {
			case PUBLISHED -> "This post is already published";
			case PUBLISHING -> "This post is already being published";
			default -> "This post can't be published right now";
			};
			throw new ResponseStatusException(HttpStatus.CONFLICT, reason);
		}
		return publishClaimed(postId);
	}

	/**
	 * Publishes a post that's already been marked PUBLISHING (used by the scheduler
	 * too).
	 */
	public PublishResult publishClaimed(UUID postId) {
		Post post = tx.execute(s -> postRepository.findForPublish(postId).orElse(null));
		if (post == null) {
			return new PublishResult(false, List.of(), "Post not found");
		}

		UUID userId = post.getUser().getId();
		List<String> done = new ArrayList<>(post.getPublishedChannels());
		List<String> errors = new ArrayList<>();

		for (String channel : post.getChannels().stream().sorted().toList()) {
			if (done.contains(channel))
				continue; // sent on an earlier attempt

			try {
				String externalId = publishTo(channel, post, userId); // CHANGED: returns id
				done.add(channel);
				tx.executeWithoutResult(s -> postRepository.findForPublish(postId)
						.ifPresent(p -> p.getPublishedChannels().add(channel)));

				// NEW: record for insights. Never let this fail the publish,
				// otherwise a retry would post the same content twice.
				try {
					metricSyncService.recordPublished(postId, userId, channel, externalId);
				} catch (Exception me) {
					log.warn("Couldn't record metrics for post {} on {}: {}", postId, channel, me.getMessage());
				}
			} catch (Exception e) {
				String message = friendly(e);
				log.warn("Publish to {} failed for post {}: {}", channel, postId, e.getMessage());
				errors.add(label(channel) + ": " + message);
			}
		}

		boolean success = errors.isEmpty();
		String error = success ? null : limit(String.join(" · ", errors), 300);

		tx.executeWithoutResult(s -> postRepository.findById(postId).ifPresent(p -> {
			if (success) {
				p.setStatus(PostStatus.PUBLISHED);
				p.setPublishedAt(Instant.now());
				p.setPublishError(null);
			} else {
				p.setStatus(PostStatus.FAILED);
				p.setPublishError(error);
			}
		}));

		return new PublishResult(success, done, error);
	}

	// ---------- routing ----------

	/** Returns the platform's id for the published post. */
	private String publishTo(String channel, Post post, UUID userId) { // CHANGED: void -> String
		SocialPlatform platform = platformOf(channel);

		ConnectedAccount account = connectedAccountRepository.findByUserIdAndPlatform(userId, platform).stream()
				.filter(ConnectedAccount::isActive).findFirst()
				.orElseThrow(() -> new PublishException(label(channel) + " isn't connected"));

		if (account.getAccessToken() == null || account.getAccessToken().isBlank()) {
			throw new PublishException("Reconnect " + label(channel));
		}
		if (account.getAccessTokenExpiresAt() != null && account.getAccessTokenExpiresAt().isBefore(Instant.now())) {
			throw new PublishException("Your " + label(channel) + " connection expired. Reconnect it.");
		}

		return switch (platform) {
		case INSTAGRAM -> publishToInstagram(post, account);
		case FACEBOOK -> publishToFacebook(post, account);
		default -> throw new PublishException("Publishing to " + label(channel) + " isn't available yet");
		};
	}

	private static SocialPlatform platformOf(String channel) {
		String c = channel.trim().toLowerCase();
		if (c.equals("x") || c.equals("twitter"))
			return SocialPlatform.TWITTER;
		try {
			return SocialPlatform.valueOf(c.toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new PublishException("Unknown channel " + channel);
		}
	}

	// ---------- Instagram ----------

	@SuppressWarnings("unchecked")
	private String publishToInstagram(Post post, ConnectedAccount account) { // CHANGED: returns id
		String igUserId = account.getPlatformUserId();
		String token = account.getAccessToken();
		if (igUserId == null || igUserId.isBlank()) {
			throw new PublishException("Reconnect Instagram");
		}

		PostMedia media = post.getMedia().stream().findFirst()
				.orElseThrow(() -> new PublishException("Instagram needs an image or video"));
		boolean video = "video".equalsIgnoreCase(media.getType());

		String caption = post.getCaption() == null ? "" : post.getCaption();

		// Step 1: create the media container
		String body = video
				? form("media_type", "REELS", "video_url", media.getUrl(), "caption", caption, "access_token", token)
				: form("image_url", MEDIA_PROXY + media.getId(), "caption", caption, "access_token", token);

		Map<String, Object> container = http.post().uri(INSTAGRAM_API + "/" + igUserId + "/media")
				.contentType(MediaType.APPLICATION_FORM_URLENCODED).body(body).retrieve().body(Map.class);

		if (container == null || container.get("id") == null) {
			throw new PublishException("Instagram didn't accept the media");
		}
		String containerId = String.valueOf(container.get("id"));

		// Videos take longer to process
		waitForInstagram(containerId, token, video ? 40 : 10, video ? 3000 : 2000);

		// Step 2: publish it
		Map<String, Object> result = http.post().uri(INSTAGRAM_API + "/" + igUserId + "/media_publish")
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.body(form("creation_id", containerId, "access_token", token)).retrieve().body(Map.class);

		if (result == null || result.get("id") == null) {
			throw new PublishException("Instagram didn't publish the post");
		}
		String mediaId = String.valueOf(result.get("id"));
		log.info("Published post {} to Instagram as {}", post.getId(), mediaId);
		return mediaId; // NEW
	}

	@SuppressWarnings("unchecked")
	private void waitForInstagram(String containerId, String token, int attempts, long delayMs) {
		for (int i = 0; i < attempts; i++) {
			sleep(delayMs);

			Map<String, Object> status = http.get()
					.uri(b -> b.scheme("https").host("graph.instagram.com").path("/v21.0/" + containerId)
							.queryParam("fields", "status_code").queryParam("access_token", token).build())
					.retrieve().body(Map.class);

			String code = status == null ? "" : String.valueOf(status.get("status_code"));
			if ("FINISHED".equalsIgnoreCase(code))
				return;
			if ("ERROR".equalsIgnoreCase(code) || "EXPIRED".equalsIgnoreCase(code)) {
				throw new PublishException("Instagram couldn't process the media");
			}
		}
		throw new PublishException("Instagram took too long to process the media. Try again.");
	}

	// ---------- Facebook ----------

	@SuppressWarnings("unchecked")
	private String publishToFacebook(Post post, ConnectedAccount account) { // CHANGED: returns id
		String pageId = account.getPlatformUserId();
		String token = account.getAccessToken();
		String caption = post.getCaption() == null ? "" : post.getCaption();

		PostMedia image = post.getMedia().stream().filter(m -> "image".equalsIgnoreCase(m.getType())).findFirst()
				.orElse(null);

		// Token goes in the body, not the URL, so it never lands in access logs
		String path = image != null ? "/photos" : "/feed";
		String body = image != null ? form("url", image.getUrl(), "message", caption, "access_token", token)
				: form("message", caption, "access_token", token);

		Map<String, Object> result = http.post().uri(FACEBOOK_API + "/" + pageId + path)
				.contentType(MediaType.APPLICATION_FORM_URLENCODED).body(body).retrieve().body(Map.class);

		if (result == null || result.get("id") == null) {
			throw new PublishException("Facebook didn't publish the post");
		}
		String fbId = String.valueOf(result.get("id"));
		log.info("Published post {} to Facebook as {}", post.getId(), fbId);
		return fbId; // NEW
	}

	// ---------- helpers ----------

	private String friendly(Exception e) {
		if (e instanceof PublishException)
			return e.getMessage();
		if (e instanceof RestClientResponseException r) {
			try {
				JsonNode err = json.readTree(r.getResponseBodyAsString()).path("error");
				int code = err.path("code").asInt();
				if (code == 190)
					return "Connection expired. Reconnect this account.";
				String message = err.path("message").asText("");
				if (!message.isBlank())
					return limit(message, 150);
			} catch (Exception ignored) {
				// fall through
			}
			return "The platform rejected the post (" + r.getStatusCode().value() + ")";
		}
		return "Something went wrong. Try again.";
	}

	private static String form(String... pairs) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i + 1 < pairs.length; i += 2) {
			if (sb.length() > 0)
				sb.append('&');
			sb.append(pairs[i]).append('=')
					.append(URLEncoder.encode(pairs[i + 1] == null ? "" : pairs[i + 1], StandardCharsets.UTF_8));
		}
		return sb.toString();
	}

	private static String label(String channel) {
		return switch (channel.toLowerCase()) {
		case "instagram" -> "Instagram";
		case "facebook" -> "Facebook";
		case "linkedin" -> "LinkedIn";
		case "youtube" -> "YouTube";
		case "x", "twitter" -> "X";
		case "whatsapp" -> "WhatsApp";
		case "threads" -> "Threads";
		default -> channel;
		};
	}

	private static String limit(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max - 1) + "…";
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new PublishException("Publishing was interrupted");
		}
	}
}