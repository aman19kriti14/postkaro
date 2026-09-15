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

		System.out.println("========== INSTAGRAM DEBUG ==========");

		System.out.println("Instagram User ID: " + igUserId);

		System.out.println("Access Token Present: true");

		System.out.println("=====================================");

		/*
		 * Instagram image publishing requires an image.
		 */
		boolean hasImage = post.getMedia() != null
				&& post.getMedia().stream().anyMatch(m -> "image".equalsIgnoreCase(m.getType()));

		if (!hasImage) {
			throw new RuntimeException("Instagram requires an image.");
		}

		PostMedia image = post.getMedia().stream().filter(m -> "image".equalsIgnoreCase(m.getType())).findFirst()
				.orElseThrow(() -> new RuntimeException("Instagram image not found."));

		String imageUrl = image.getUrl();

		if (imageUrl == null || imageUrl.isBlank()) {
			throw new RuntimeException("Instagram image URL is missing.");
		}

		/*
		 * ========================================================= STEP 1 - CREATE
		 * MEDIA CONTAINER =========================================================
		 *
		 * IMPORTANT: Use a MultiValueMap so Spring sends exactly:
		 *
		 * Content-Type: application/x-www-form-urlencoded
		 *
		 * This matches the curl request that successfully worked.
		 */

		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();

		form.add("image_url", imageUrl);
		form.add("caption", post.getCaption() != null ? post.getCaption() : "");
		form.add("access_token", token);

		System.out.println("IG IMAGE URL: " + imageUrl);

		System.out.println("IG CREATE CONTAINER: " + INSTAGRAM_GRAPH_API + "/" + igUserId + "/media");

		Map<String, Object> container = restClient.post().uri(INSTAGRAM_GRAPH_API + "/" + igUserId + "/media")
				.contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(Map.class);

		if (container == null) {
			throw new RuntimeException("Instagram returned an empty container response.");
		}

		System.out.println("IG CONTAINER RESPONSE: " + container);

		Object containerIdObject = container.get("id");

		if (containerIdObject == null) {
			throw new RuntimeException("Instagram media container was not created. Response: " + container);
		}

		String containerId = String.valueOf(containerIdObject);

		System.out.println("IG CONTAINER ID: " + containerId);

		/*
		 * ========================================================= STEP 2 - PUBLISH
		 * MEDIA CONTAINER =========================================================
		 */

		MultiValueMap<String, String> publishForm = new LinkedMultiValueMap<>();

		publishForm.add("creation_id", containerId);

		publishForm.add("access_token", token);

		System.out.println("IG PUBLISH CONTAINER: " + INSTAGRAM_GRAPH_API + "/" + igUserId + "/media_publish");

		Map<String, Object> result = restClient.post().uri(INSTAGRAM_GRAPH_API + "/" + igUserId + "/media_publish")
				.contentType(MediaType.APPLICATION_FORM_URLENCODED).body(publishForm).retrieve().body(Map.class);

		if (result == null) {
			throw new RuntimeException("Instagram returned an empty publish response.");
		}

		System.out.println("IG PUBLISH RESPONSE: " + result);

		Object publishedId = result.get("id");

		if (publishedId == null) {
			throw new RuntimeException("Instagram publishing failed. Response: " + result);
		}

		System.out.println("PUBLISHED to Instagram: " + publishedId);
	}

	/**
	 * Facebook publishing
	 */
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
}