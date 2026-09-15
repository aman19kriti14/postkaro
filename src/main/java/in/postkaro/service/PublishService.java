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

	private static final String GRAPH_API = "https://graph.instagram.com/v21.0";

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

	@SuppressWarnings("unchecked")
	private void publishToInstagram(Post post, ConnectedAccount account) {

		String igUserId = account.getPlatformUserId();
		String token = account.getAccessToken();

		System.out.println("========== INSTAGRAM DEBUG ==========");

		System.out.println("Instagram User ID: " + igUserId);

		System.out.println("Access Token Present: " + (token != null && !token.isBlank()));

		System.out.println("=====================================");

		/*
		 * Instagram requires an image for this publishing flow.
		 */
		boolean hasImage = post.getMedia() != null
				&& post.getMedia().stream().anyMatch(m -> "image".equals(m.getType()));

		if (!hasImage) {
			throw new RuntimeException("Instagram requires an image.");
		}

		PostMedia image = post.getMedia().stream().filter(m -> "image".equals(m.getType())).findFirst()
				.orElseThrow(() -> new RuntimeException("Instagram image not found."));

		String imageUrl = image.getUrl();

		/*
		 * STEP 1: Create Instagram media container.
		 *
		 * IMPORTANT: Send parameters as application/x-www-form-urlencoded, matching the
		 * successful curl request.
		 */

		String formBody = "image_url=" + URLEncoder.encode(imageUrl, StandardCharsets.UTF_8) + "&caption="
				+ URLEncoder.encode(post.getCaption(), StandardCharsets.UTF_8) + "&access_token="
				+ URLEncoder.encode(token, StandardCharsets.UTF_8);

		System.out.println("IG IMAGE URL: " + imageUrl);

		System.out.println("IG CREATE CONTAINER: " + GRAPH_API + "/" + igUserId + "/media");

		Map<String, Object> container = restClient.post().uri(GRAPH_API + "/" + igUserId + "/media")
				.contentType(MediaType.APPLICATION_FORM_URLENCODED).body(formBody).retrieve().body(Map.class);

		if (container == null || container.get("id") == null) {

			throw new RuntimeException("Instagram media container was not created. Response: " + container);
		}

		String containerId = String.valueOf(container.get("id"));

		System.out.println("IG CONTAINER ID: " + containerId);

		/*
		 * STEP 2: Publish the created media container.
		 */

		String publishFormBody = "creation_id=" + URLEncoder.encode(containerId, StandardCharsets.UTF_8)
				+ "&access_token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);

		System.out.println("IG PUBLISH CONTAINER: " + GRAPH_API + "/" + igUserId + "/media_publish");

		Map<String, Object> result = restClient.post().uri(GRAPH_API + "/" + igUserId + "/media_publish")
				.contentType(MediaType.APPLICATION_FORM_URLENCODED).body(publishFormBody).retrieve().body(Map.class);

		if (result == null || result.get("id") == null) {

			throw new RuntimeException("Instagram publishing failed. Response: " + result);
		}

		System.out.println("PUBLISHED to Instagram: " + result.get("id"));
	}

	@SuppressWarnings("unchecked")
	private void publishToFacebook(Post post, ConnectedAccount account) {

		String pageId = account.getPlatformUserId();
		String token = account.getAccessToken();

		boolean hasImage = post.getMedia() != null
				&& post.getMedia().stream().anyMatch(m -> "image".equals(m.getType()));

		if (hasImage) {

			PostMedia image = post.getMedia().stream().filter(m -> "image".equals(m.getType())).findFirst()
					.orElseThrow();

			restClient.post()
					.uri(GRAPH_API + "/" + pageId + "/photos" + "?url="
							+ URLEncoder.encode(image.getUrl(), StandardCharsets.UTF_8) + "&message="
							+ URLEncoder.encode(post.getCaption(), StandardCharsets.UTF_8) + "&access_token="
							+ URLEncoder.encode(token, StandardCharsets.UTF_8))
					.retrieve().body(Map.class);

		} else {

			restClient.post()
					.uri(GRAPH_API + "/" + pageId + "/feed" + "?message="
							+ URLEncoder.encode(post.getCaption(), StandardCharsets.UTF_8) + "&access_token="
							+ URLEncoder.encode(token, StandardCharsets.UTF_8))
					.retrieve().body(Map.class);
		}

		System.out.println("PUBLISHED to Facebook page: " + pageId);
	}
}