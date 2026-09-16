package in.postkaro.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import in.postkaro.entity.ConnectedAccount;
import in.postkaro.entity.Post;
import in.postkaro.entity.PostMedia;
import in.postkaro.enums.PostStatus;
import in.postkaro.enums.SocialPlatform;
import in.postkaro.repository.ConnectedAccountRepository;
import in.postkaro.repository.PostRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PublishService {

	private final PostRepository postRepository;
	private final ConnectedAccountRepository connectedAccountRepository;

	private final RestClient restClient = RestClient.create();

	private static final String INSTAGRAM_GRAPH_API = "https://graph.instagram.com/v21.0";

	private static final String FACEBOOK_GRAPH_API = "https://graph.facebook.com/v21.0";

	@Transactional
	public void publishPost(UUID postId, UUID userId) {

		Post post = postRepository.findByIdAndUserId(postId, userId)
				.orElseThrow(() -> new RuntimeException("Post not found"));

		post.setStatus(PostStatus.PUBLISHING);
		postRepository.save(post);

		try {

			for (String channel : post.getChannels()) {

				SocialPlatform platform = SocialPlatform.valueOf(channel.toUpperCase());

				List<ConnectedAccount> accounts = connectedAccountRepository.findByUserIdAndPlatform(userId, platform);

				if (accounts.isEmpty()) {
					throw new RuntimeException("No connected account found for " + platform);
				}

				ConnectedAccount account = accounts.get(0);

				if (platform == SocialPlatform.INSTAGRAM) {

					publishToInstagram(post, account);

				} else if (platform == SocialPlatform.FACEBOOK) {

					publishToFacebook(post, account);
				}
			}

			post.setStatus(PostStatus.PUBLISHED);
			post.setPublishedAt(Instant.now());

		} catch (Exception e) {

			post.setStatus(PostStatus.FAILED);

			System.out.println("PUBLISH FAILED: " + e.getMessage());

			e.printStackTrace();
		}

		postRepository.save(post);
	}

	/**
	 * Instagram publishing
	 *
	 * Flow:
	 *
	 * 1. Create media container 2. Publish media container
	 */
	@SuppressWarnings("unchecked")
	private void publishToInstagram(Post post, ConnectedAccount account) {

		String igUserId = account.getPlatformUserId();
		String token = account.getAccessToken();

		if (igUserId == null || igUserId.isBlank()) {
			throw new RuntimeException("Instagram User ID is missing.");
		}

		if (token == null || token.isBlank()) {
			throw new RuntimeException("Instagram access token is missing.");
		}

		boolean hasImage = post.getMedia() != null
				&& post.getMedia().stream().anyMatch(m -> "image".equalsIgnoreCase(m.getType()));

		if (!hasImage) {
			throw new RuntimeException("Instagram requires an image.");
		}

		PostMedia image = post.getMedia().stream().filter(m -> "image".equalsIgnoreCase(m.getType())).findFirst()
				.orElseThrow(() -> new RuntimeException("Instagram image not found."));

		String imageUrl = "https://postkaro-production.up.railway.app/api/v1/media/" + image.getId();

		System.out.println("IG IMAGE URL: " + imageUrl);

		if (imageUrl == null || imageUrl.isBlank()) {
			throw new RuntimeException("Instagram image URL is missing.");
		}

		String caption = post.getCaption() != null ? post.getCaption() : "";

		System.out.println("========== INSTAGRAM DEBUG ==========");
		System.out.println("Instagram User ID: " + igUserId);
		System.out.println("Access Token Present: true");
		System.out.println("IG IMAGE URL: " + imageUrl);
		System.out.println("=====================================");

		/*
		 * ============================================================ STEP 1: CREATE
		 * MEDIA CONTAINER ============================================================
		 */

		String formBody = "image_url=" + URLEncoder.encode(imageUrl, StandardCharsets.UTF_8) + "&caption="
				+ URLEncoder.encode(caption, StandardCharsets.UTF_8) + "&access_token="
				+ URLEncoder.encode(token, StandardCharsets.UTF_8);

		System.out.println("IG CREATE CONTAINER: " + INSTAGRAM_GRAPH_API + "/" + igUserId + "/media");

		Map<String, Object> container = restClient.post().uri(INSTAGRAM_GRAPH_API + "/" + igUserId + "/media")
				.contentType(MediaType.APPLICATION_FORM_URLENCODED).body(formBody).retrieve().body(Map.class);

		System.out.println("IG CONTAINER RESPONSE: " + container);

		if (container == null || container.get("id") == null) {
			throw new RuntimeException("Instagram media container was not created. Response: " + container);
		}

		String containerId = String.valueOf(container.get("id"));

		System.out.println("IG CONTAINER ID: " + containerId);

		waitForInstagramMediaReady(containerId, token);

		/*
		 * ============================================================ STEP 2: PUBLISH
		 * CONTAINER ============================================================
		 */

		String publishBody = "creation_id=" + URLEncoder.encode(containerId, StandardCharsets.UTF_8) + "&access_token="
				+ URLEncoder.encode(token, StandardCharsets.UTF_8);

		System.out.println("IG PUBLISH CONTAINER: " + INSTAGRAM_GRAPH_API + "/" + igUserId + "/media_publish");

		Map<String, Object> result = restClient.post().uri(INSTAGRAM_GRAPH_API + "/" + igUserId + "/media_publish")
				.contentType(MediaType.APPLICATION_FORM_URLENCODED).body(publishBody).retrieve().body(Map.class);

		System.out.println("IG PUBLISH RESPONSE: " + result);

		if (result == null || result.get("id") == null) {
			throw new RuntimeException("Instagram publishing failed. Response: " + result);
		}

		System.out.println("PUBLISHED to Instagram: " + result.get("id"));
	}

	private String getInstagramImageUrl(String imageUrl) {
		if (imageUrl == null || imageUrl.isBlank()) {
			throw new RuntimeException("Image URL is missing.");
		}

		if (imageUrl.contains("/image/upload/")) {
			return imageUrl.replace("/image/upload/", "/image/upload/f_jpg,q_auto/");
		}

		return imageUrl;
	}

	@SuppressWarnings("unchecked")
	private void publishToFacebook(Post post, ConnectedAccount account) {

		String pageId = account.getPlatformUserId();
		String token = account.getAccessToken();

		boolean hasImage = post.getMedia() != null
				&& post.getMedia().stream().anyMatch(m -> "image".equalsIgnoreCase(m.getType()));

		if (hasImage) {

			PostMedia image = post.getMedia().stream().filter(m -> "image".equalsIgnoreCase(m.getType())).findFirst()
					.orElseThrow();

			restClient.post()
					.uri(FACEBOOK_GRAPH_API + "/" + pageId + "/photos" + "?url="
							+ URLEncoder.encode(image.getUrl(), StandardCharsets.UTF_8) + "&message="
							+ URLEncoder.encode(post.getCaption() != null ? post.getCaption() : "",
									StandardCharsets.UTF_8)
							+ "&access_token=" + URLEncoder.encode(token, StandardCharsets.UTF_8))
					.retrieve().body(Map.class);

		} else {

			restClient.post()
					.uri(FACEBOOK_GRAPH_API + "/" + pageId + "/feed" + "?message="
							+ URLEncoder.encode(post.getCaption() != null ? post.getCaption() : "",
									StandardCharsets.UTF_8)
							+ "&access_token=" + URLEncoder.encode(token, StandardCharsets.UTF_8))
					.retrieve().body(Map.class);
		}

		System.out.println("PUBLISHED to Facebook page: " + pageId);
	}

	private void waitForInstagramMediaReady(String containerId, String token) {

		for (int attempt = 1; attempt <= 10; attempt++) {

			try {
				Thread.sleep(2000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new RuntimeException("Interrupted while waiting for Instagram media.", e);
			}

			System.out.println("Checking Instagram media status. Attempt: " + attempt);

			Map<String, Object> status = restClient.get()
					.uri(uriBuilder -> uriBuilder.scheme("https").host("graph.instagram.com")
							.path("/v21.0/" + containerId).queryParam("fields", "status_code,status")
							.queryParam("access_token", token).build())
					.retrieve().body(Map.class);

			System.out.println("IG MEDIA STATUS: " + status);

			if (status == null) {
				continue;
			}

			String statusCode = String.valueOf(status.get("status_code"));

			if ("FINISHED".equalsIgnoreCase(statusCode)) {
				System.out.println("Instagram media is READY.");
				return;
			}

			if ("ERROR".equalsIgnoreCase(statusCode) || "EXPIRED".equalsIgnoreCase(statusCode)) {

				throw new RuntimeException("Instagram media processing failed: " + status);
			}
		}

		throw new RuntimeException("Instagram media was not ready after waiting.");
	}
}