package in.postkaro.service;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import in.postkaro.entity.Campaign;
import in.postkaro.entity.Post;
import in.postkaro.enums.CampaignStatus;
import in.postkaro.enums.PostStatus;
import in.postkaro.repository.CampaignRepository;
import in.postkaro.repository.PostRepository;
import lombok.RequiredArgsConstructor;

// Unschedule / delete posts, stop / delete campaigns.
// Every status change is a conditional UPDATE, so it can't race with
// PostPublishScheduler.claim(): whichever commits first wins.
@Service
@RequiredArgsConstructor
public class ScheduleControlService {

	private final PostRepository postRepository;
	private final CampaignRepository campaignRepository;

	// ---------- posts ----------

	// Scheduled -> Draft. Keeps scheduledAt so it can be rescheduled.
	@Transactional
	public Map<String, Object> unschedulePost(UUID postId, UUID userId) {
		Post post = ownedPost(postId, userId);

		int changed = postRepository.moveStatus(post.getId(), PostStatus.SCHEDULED, PostStatus.DRAFT, Instant.now());
		if (changed == 0) {
			throw conflict(post.getStatus() == PostStatus.PUBLISHING ? "This post is already publishing."
					: "Only scheduled posts can be unscheduled.");
		}
		return Map.of("id", post.getId().toString(), "status", PostStatus.DRAFT.name());
	}

	// Deletes a draft / needs-review / scheduled / failed post.
	// Never one that's publishing or has gone out on any channel.
	@Transactional
	public void deletePost(UUID postId, UUID userId) {
		Post post = ownedPost(postId, userId);

		// Checked before any bulk update, while the entity is still managed
		if (!post.getPublishedChannels().isEmpty()) {
			// Failed on some channels but already live on others: keep it (it has metrics)
			throw conflict("This post is already live on " + String.join(", ", post.getPublishedChannels())
					+ " and can't be deleted.");
		}

		switch (post.getStatus()) {
		case PUBLISHING -> throw conflict("This post is publishing right now. Try again in a minute.");
		case PUBLISHED -> throw conflict("Published posts can't be deleted here. Remove it on the platform instead.");
		case SCHEDULED -> {
			// Pull it out of the queue first so the scheduler can't grab it mid-delete
			int changed = postRepository.moveStatus(post.getId(), PostStatus.SCHEDULED, PostStatus.DRAFT,
					Instant.now());
			if (changed == 0)
				throw conflict("This post just started publishing and can't be deleted.");
		}
		default -> {
		}
		}

		// Re-read after the bulk update cleared the context; entity delete
		// cascades to media, channels and published channels
		postRepository.findById(post.getId()).ifPresent(postRepository::delete);
	}

	// ---------- campaigns ----------

	// Stops future publishing. Scheduled posts go back to Draft; published ones
	// stay as they are.
	@Transactional
	public Map<String, Object> stopCampaign(UUID campaignId, UUID userId) {
		Campaign c = ownedCampaign(campaignId, userId);

		if (c.getStatus() == CampaignStatus.STOPPED) {
			return Map.of("id", c.getId().toString(), "status", c.getStatus().name(), "unscheduled", 0,
					"stillPublishing", 0L);
		}
		if (c.getStatus() == CampaignStatus.DRAFT) {
			throw conflict("This campaign hasn't been published yet. Delete it instead.");
		}

		int unscheduled = postRepository.moveCampaignPosts(c.getId(),
				EnumSet.of(PostStatus.SCHEDULED, PostStatus.NEEDS_REVIEW), PostStatus.DRAFT, Instant.now());

		// Bulk update cleared the persistence context, so reload before saving
		Campaign fresh = campaignRepository.findById(campaignId).orElseThrow();
		fresh.setStatus(CampaignStatus.STOPPED);
		campaignRepository.save(fresh);

		long stillPublishing = postRepository.countByCampaignIdAndStatus(c.getId(), PostStatus.PUBLISHING);

		return Map.of("id", c.getId().toString(), "status", CampaignStatus.STOPPED.name(), "unscheduled", unscheduled,
				"stillPublishing", stillPublishing); // UI: "1 post was already going out"
	}

	// Deletes the campaign and its unpublished posts. Posts that went out are
	// kept (with their metrics), just unlinked from the campaign.
	@Transactional
	public Map<String, Object> deleteCampaign(UUID campaignId, UUID userId) {
		Campaign c = ownedCampaign(campaignId, userId);

		// Freeze the queue first so nothing gets claimed while we delete
		postRepository.moveCampaignPosts(c.getId(), EnumSet.of(PostStatus.SCHEDULED), PostStatus.DRAFT, Instant.now());

		if (postRepository.countByCampaignIdAndStatus(c.getId(), PostStatus.PUBLISHING) > 0) {
			// Throwing rolls back the freeze above too
			throw conflict("A post in this campaign is publishing right now. Try again in a minute.");
		}

		List<Post> posts = postRepository.findByCampaignIdOrderByScheduledAtAsc(c.getId());
		List<Post> toDelete = posts.stream()
				.filter(p -> p.getStatus() != PostStatus.PUBLISHED && p.getPublishedChannels().isEmpty()).toList();
		int kept = posts.size() - toDelete.size();

		postRepository.deleteAll(toDelete); // entity delete, so child tables are cleaned up
		postRepository.flush();
		postRepository.detachFromCampaign(c.getId());
		campaignRepository.deleteById(c.getId());

		return Map.of("deletedPosts", toDelete.size(), "keptPublished", kept);
	}

	// ---------- helpers ----------

	private Post ownedPost(UUID id, UUID userId) {
		// 404 for someone else's post too, so we don't reveal which ids exist
		return postRepository.findByIdAndUserId(id, userId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found"));
	}

	private Campaign ownedCampaign(UUID id, UUID userId) {
		return campaignRepository.findByIdAndUserId(id, userId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Campaign not found"));
	}

	private static ResponseStatusException conflict(String msg) {
		return new ResponseStatusException(HttpStatus.CONFLICT, msg);
	}
}