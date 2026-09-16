package in.postkaro.service;

import java.time.Instant;
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

		// Save media if present
		String mediaUrl = (String) data.get("mediaUrl");
		String mediaType = (String) data.get("mediaType");
		System.out.println("DRAFT MEDIA: url=" + mediaUrl + " type=" + mediaType);

		if (mediaUrl != null && !mediaUrl.isBlank()) {
			PostMedia media = PostMedia.builder().post(post).type(mediaType != null ? mediaType : "image").url(mediaUrl)
					.dimensions("1024x1024").sortOrder(0).build();
			post.getMedia().add(media);
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
		if (data.containsKey("mediaUrl")) {
			String mediaUrl = (String) data.get("mediaUrl");
			String mediaType = (String) data.get("mediaType");

			post.getMedia().clear();
			if (mediaUrl != null && !mediaUrl.isBlank()) {
				post.getMedia().add(PostMedia.builder().post(post).type(mediaType != null ? mediaType : "image")
						.url(mediaUrl).dimensions("1024x1024").sortOrder(0).build());
			}
		}

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
}