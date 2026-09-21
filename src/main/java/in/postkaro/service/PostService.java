package in.postkaro.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import in.postkaro.entity.Post;
import in.postkaro.entity.PostMedia;
import in.postkaro.entity.User;
import in.postkaro.enums.PostStatus;
import in.postkaro.repository.PostRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PostService {

	private final PostRepository postRepository;

	@Transactional
	public Post createDraft(User user, Map<String, Object> data) {
		Post post = Post.builder().user(user).caption((String) data.get("caption")).prompt((String) data.get("prompt"))
				.tone((String) data.get("tone")).status(PostStatus.DRAFT).build();

		if (data.get("channels") instanceof List) {
			post.setChannels(new HashSet<>((List<String>) data.get("channels")));
		}

		post = postRepository.save(post);

		// Save media if present (single mediaUrl or an ordered media list for
		// carousels)
		if (applyMedia(post, data)) {
			post = postRepository.save(post);
		}

		return post;
	}

	@Transactional
	public Post updatePost(UUID postId, UUID userId, Map<String, Object> data) {
		Post post = postRepository.findByIdAndUserId(postId, userId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found"));

		if (post.getStatus() != PostStatus.DRAFT) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Only drafts can be edited");
		}

		if (data.containsKey("caption"))
			post.setCaption((String) data.get("caption"));
		if (data.containsKey("prompt"))
			post.setPrompt((String) data.get("prompt"));
		if (data.containsKey("tone"))
			post.setTone((String) data.get("tone"));
		if (data.get("channels") instanceof List)
			post.setChannels(new HashSet<>((List<String>) data.get("channels")));

		// Media: replace whatever was there with what the page sends
		applyMedia(post, data);

		// Planned time for a draft (campaign slot); doesn't schedule it
		if (data.containsKey("plannedAt")) {
			Object v = data.get("plannedAt");
			if (v == null || v.toString().isBlank()) {
				post.setScheduledAt(null);
			} else {
				try {
					post.setScheduledAt(Instant.parse(v.toString()));
				} catch (Exception e) {
					throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Pick a valid date and time");
				}
			}
		}

		return postRepository.save(post);
	}

	@Transactional
	public Post schedulePost(UUID postId, UUID userId, Instant scheduledAt) {
		Post post = postRepository.findByIdAndUserId(postId, userId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found"));

		if (post.getStatus() != PostStatus.DRAFT) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Only drafts can be scheduled");
		}
		if (post.getMedia() == null || post.getMedia().isEmpty()) {
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Add a visual before scheduling");
		}
		if (post.getChannels() == null || post.getChannels().isEmpty()) {
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
					"Pick at least one channel before scheduling");
		}
		if (post.getCaption() == null || post.getCaption().isBlank()) {
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Add a caption before scheduling");
		}
		if (scheduledAt == null || scheduledAt.isBefore(Instant.now().plusSeconds(120))) {
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
					"Pick a time at least 2 minutes from now");
		}

		post.setStatus(PostStatus.SCHEDULED);
		post.setScheduledAt(scheduledAt);
		return postRepository.save(post);
	}

	public List<Post> getUserPosts(UUID userId) {
		return postRepository.findByUserIdOrderByCreatedAtDesc(userId);
	}

	@Transactional(readOnly = true)
	public Post getPost(UUID postId, UUID userId) {
		Post post = postRepository.findByIdAndUserId(postId, userId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found"));
		post.getMedia().size(); // load media before the transaction closes
		return post;
	}

	public List<Post> getUserDrafts(UUID userId) {
		return postRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, PostStatus.DRAFT);
	}

	// ---------- media ----------

	private static final int MAX_MEDIA = 10;

	/**
	 * Replaces the post's media with what the page sent. Accepts either: - "media":
	 * [{ "url": "...", "type": "image" | "video" }, ...] in slide order (carousel),
	 * or - "mediaUrl" + "mediaType" (single item, what older pages send). "media"
	 * wins when both are present. Returns true if media was touched.
	 */
	@SuppressWarnings("unchecked")
	private boolean applyMedia(Post post, Map<String, Object> data) {
		List<String[]> items = new ArrayList<>(); // [url, type]

		if (data.get("media") instanceof List<?> list) {
			for (Object o : list) {
				if (!(o instanceof Map<?, ?> m))
					continue;
				Object url = m.get("url");
				Object type = m.get("type");
				if (url instanceof String u && !u.isBlank()) {
					items.add(new String[] { u, "video".equals(type) ? "video" : "image" });
				}
			}
		} else if (data.containsKey("mediaUrl")) {
			String mediaUrl = (String) data.get("mediaUrl");
			String mediaType = (String) data.get("mediaType");
			if (mediaUrl != null && !mediaUrl.isBlank()) {
				items.add(new String[] { mediaUrl, "video".equals(mediaType) ? "video" : "image" });
			}
		} else {
			return false; // page didn't send media — leave it alone
		}

		if (items.size() > MAX_MEDIA) {
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
					"A carousel can have at most " + MAX_MEDIA + " slides");
		}
		for (String[] it : items) {
			if (!it[0].startsWith("https://") && !it[0].startsWith("http://")) {
				throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "One of the media links is invalid");
			}
		}

		post.getMedia().clear();
		for (int i = 0; i < items.size(); i++) {
			post.getMedia().add(PostMedia.builder().post(post).type(items.get(i)[1]).url(items.get(i)[0])
					.dimensions("1024x1024").sortOrder(i).build());
		}

		// Keep the format in step with the media so calendar/analytics label it right
		if (items.size() > 1) {
			post.setFormat("carousel");
		} else if ("carousel".equals(post.getFormat())) {
			post.setFormat(items.isEmpty() || "image".equals(items.get(0)[1]) ? "post" : "reel");
		}
		return true;
	}
}