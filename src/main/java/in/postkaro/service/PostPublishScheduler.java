package in.postkaro.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import in.postkaro.enums.PostStatus;
import in.postkaro.repository.PostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class PostPublishScheduler {

	private final PostRepository postRepository;
	private final TransactionTemplate tx;
	// private final PostPublishService publishService; ← wired in once I see your
	// publish code

	@Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
	public void publishDuePosts() {
		for (UUID id : postRepository.findDueIds(Instant.now())) {
			Integer claimed = tx.execute(s -> postRepository.claim(id));
			if (claimed == null || claimed == 0)
				continue; // already taken

			try {
				log.info("Publishing scheduled post {}", id);
				// publishService.publish(id); ← must set PUBLISHED + publishedAt on success
			} catch (Exception e) {
				log.error("Scheduled publish failed for post {}", id, e);
				tx.executeWithoutResult(
						s -> postRepository.findById(id).ifPresent(p -> p.setStatus(PostStatus.FAILED)));
			}
		}
	}
}