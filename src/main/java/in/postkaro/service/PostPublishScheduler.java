package in.postkaro.service;

import java.time.Duration;
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
	private final PublishService publishService;
	private final TransactionTemplate tx;
	private final CreditService creditService;

	@Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
	public void publishDuePosts() {
		Instant now = Instant.now();

		Integer stuck = tx.execute(s -> postRepository.failStuck(now.minus(Duration.ofMinutes(15))));
		if (stuck != null && stuck > 0) {
			log.warn("Marked {} stuck posts as failed", stuck);
		}

		for (UUID id : postRepository.findDueIds(now)) {

			// Trial or plan has ended: don't publish, tell the user why
			UUID ownerId = tx.execute(s -> postRepository.findById(id).map(p -> p.getUser().getId()).orElse(null));

			if (ownerId != null && !creditService.hasAccess(ownerId)) {
				tx.executeWithoutResult(s -> postRepository.findById(id).ifPresent(p -> {
					p.setStatus(PostStatus.FAILED);
					p.setPublishError("Your free trial has ended. Choose a plan to publish scheduled posts.");
				}));
				log.info("Skipped scheduled post {}: owner {} has no active plan", id, ownerId);
				continue;
			}

			Integer claimed = tx.execute(s -> postRepository.claim(id, Instant.now()));
			if (claimed == null || claimed == 0)
				continue; // someone else took it

			try {
				PublishService.PublishResult result = publishService.publishClaimed(id);
				if (result.success()) {
					log.info("Scheduled post {} published to {}", id, result.published());
				} else {
					log.warn("Scheduled post {} failed: {}", id, result.error());
				}
			} catch (Exception e) {
				log.error("Scheduled publish crashed for post {}", id, e);
				tx.executeWithoutResult(s -> postRepository.findById(id).ifPresent(p -> {
					p.setStatus(PostStatus.FAILED);
					p.setPublishError("Something went wrong. Try again.");
				}));
			}
		}
	}
}