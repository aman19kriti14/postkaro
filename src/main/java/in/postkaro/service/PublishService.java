package in.postkaro.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

	private static final String GRAPH_API = "https://graph.facebook.com/v21.0";

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

				if (accounts.isEmpty())
					continue;

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

		System.out.println("IG PUBLISH: userId=" + igUserId + " tokenLength=" + (token != null ? token.length() : 0));

		boolean hasImage = post.getMedia() != null
				&& post.getMedia().stream().anyMatch(m -> "image".equals(m.getType()));

		if (!hasImage) {
			System.out.println("Instagram requires an image. Skipping.");
			return;
		}

		PostMedia image = post.getMedia().stream().filter(m -> "image".equals(m.getType())).findFirst().orElseThrow();

		// Step 1: Create media container
		String containerUrl = "https://graph.instagram.com/v21.0/" + igUserId + "/media" + "?image_url="
				+ java.net.URLEncoder.encode(image.getUrl(), java.nio.charset.StandardCharsets.UTF_8) + "&caption="
				+ java.net.URLEncoder.encode(post.getCaption(), java.nio.charset.StandardCharsets.UTF_8)
				+ "&access_token=" + token;

		System.out.println("IG CONTAINER URL: " + containerUrl.substring(0, Math.min(200, containerUrl.length())));

		Map<String, Object> container = restClient.post().uri(containerUrl).retrieve().body(Map.class);

		String containerId = (String) container.get("id");
		System.out.println("IG CONTAINER ID: " + containerId);

		// Step 2: Publish
		String publishUrl = "https://graph.instagram.com/v21.0/" + igUserId + "/media_publish" + "?creation_id="
				+ containerId + "&access_token=" + token;

		Map<String, Object> result = restClient.post().uri(publishUrl).retrieve().body(Map.class);

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
					.uri(GRAPH_API + "/" + pageId + "/photos" + "?url=" + image.getUrl() + "&message="
							+ java.net.URLEncoder.encode(post.getCaption(), java.nio.charset.StandardCharsets.UTF_8)
							+ "&access_token=" + token)
					.retrieve().body(Map.class);
		} else {
			// Text-only post
			restClient.post()
					.uri(GRAPH_API + "/" + pageId + "/feed" + "?message="
							+ java.net.URLEncoder.encode(post.getCaption(), java.nio.charset.StandardCharsets.UTF_8)
							+ "&access_token=" + token)
					.retrieve().body(Map.class);
		}

		System.out.println("PUBLISHED to Facebook page: " + pageId);
	}
}