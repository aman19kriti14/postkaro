package in.postkaro.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import in.postkaro.entity.Post;
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
			post.setChannels((List<String>) data.get("channels"));
		}

		return postRepository.save(post);
	}

	@Transactional
	public Post updatePost(UUID postId, UUID userId, Map<String, Object> data) {
		Post post = postRepository.findByIdAndUserId(postId, userId)
				.orElseThrow(() -> new RuntimeException("Post not found"));

		if (data.containsKey("caption"))
			post.setCaption((String) data.get("caption"));
		if (data.containsKey("prompt"))
			post.setPrompt((String) data.get("prompt"));
		if (data.containsKey("tone"))
			post.setTone((String) data.get("tone"));
		if (data.get("channels") instanceof List)
			post.setChannels((List<String>) data.get("channels"));

		return postRepository.save(post);
	}

	@Transactional
	public Post schedulePost(UUID postId, UUID userId, Instant scheduledAt) {
		Post post = postRepository.findByIdAndUserId(postId, userId)
				.orElseThrow(() -> new RuntimeException("Post not found"));

		post.setStatus(PostStatus.SCHEDULED);
		post.setScheduledAt(scheduledAt);
		return postRepository.save(post);
	}

	public List<Post> getUserPosts(UUID userId) {
		return postRepository.findByUserIdOrderByCreatedAtDesc(userId);
	}

	public List<Post> getUserDrafts(UUID userId) {
		return postRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, PostStatus.DRAFT);
	}
}