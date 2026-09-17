package in.postkaro.service;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;

import in.postkaro.dto.request.AiStudioDtos.IdeaSetView;
import in.postkaro.dto.request.AiStudioDtos.IdeaView;
import in.postkaro.entity.Idea;
import in.postkaro.entity.IdeaSet;
import in.postkaro.entity.Post;
import in.postkaro.entity.PostMedia;
import in.postkaro.enums.PostStatus;
import in.postkaro.repository.IdeaRepository;
import in.postkaro.repository.IdeaSetRepository;
import in.postkaro.repository.PostRepository;
import in.postkaro.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class IdeaActionService {

	private static final String FAL_URL = "https://fal.run/fal-ai/flux-pro/v1.1";

	private final IdeaRepository ideas;
	private final IdeaSetRepository sets;
	private final PostRepository posts;
	private final UserRepository users;
	private final AiStudioService studio;
	private final TransactionTemplate tx;
	private final BrandSettingsService brandSettings;

	private final RestClient http = RestClient.create();

	@Value("${FAL_API_KEY}")
	private String falKey;

	// ---------- dismiss / restore ----------

	@Transactional
	public IdeaView dismiss(UUID userId, UUID ideaId) {
		Idea i = owned(userId, ideaId);
		if (i.getStatus() == Idea.Status.DRAFTED) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "This idea already has a draft");
		}
		i.setStatus(Idea.Status.DISMISSED);
		return view(i);
	}

	@Transactional
	public IdeaSetView restoreDismissed(UUID userId, UUID setId) {
		IdeaSet s = sets.findByIdAndUserId(setId, userId).orElseThrow(this::setNotFound);
		ideas.restoreDismissed(s.getId());
		ideas.flush();
		return studio.get(userId, setId);
	}

	// ---------- save set ----------

	@Transactional
	public IdeaSetView save(UUID userId, UUID setId, String name) {
		IdeaSet s = sets.findByIdAndUserId(setId, userId).orElseThrow(this::setNotFound);
		if (name != null && !name.isBlank()) {
			s.setName(name.trim().length() > 80 ? name.trim().substring(0, 80) : name.trim());
		}
		s.setSaved(true);
		sets.flush();
		return studio.get(userId, setId);
	}

	// ---------- draft (also used by Post now / Schedule) ----------

	/** Returns the post id. Calling it twice returns the same draft. */
	@Transactional
	public UUID draft(UUID userId, UUID ideaId) {
		Idea i = owned(userId, ideaId);

		if (i.getDraftPostId() != null && posts.existsById(i.getDraftPostId())) {
			return i.getDraftPostId();
		}

		Post post = Post.builder().user(users.getReferenceById(userId)).title(i.getTitle())
				.caption(i.getCaption() != null ? i.getCaption() : i.getDescription())
				.prompt(i.getDescription().length() > 500 ? i.getDescription().substring(0, 500) : i.getDescription())
				.format(i.getFormat()).channels(new LinkedHashSet<>(i.getChannels())).status(PostStatus.DRAFT).build();

		if (i.getVisualUrl() != null) {
			// ⚠️ ADAPT: match your PostMedia fields
			PostMedia m = new PostMedia();
			m.setPost(post);
			m.setUrl(i.getVisualUrl());
			m.setType("image");
			post.getMedia().add(m);
		}

		Post saved = posts.save(post);
		i.setDraftPostId(saved.getId());
		i.setStatus(Idea.Status.DRAFTED);
		return saved.getId();
	}

	// ---------- make visual ----------

	/**
	 * fal.ai call happens outside a transaction; only the save is transactional.
	 */
	public IdeaView makeVisual(UUID userId, UUID ideaId) {
		Idea snapshot = tx.execute(s -> owned(userId, ideaId));

		String size = switch (snapshot.getFormat()) {
		case "reel", "story" -> "portrait_16_9";
		case "carousel", "post" -> "square_hd";
		default -> "square_hd";
		};

		String palette = brandSettings.paletteHint(userId);
		String prompt = "Editorial product photograph for a social media " + snapshot.getFormat() + ". "
				+ snapshot.getTitle() + ". " + snapshot.getDescription()
				+ (palette.isBlank() ? "" : " Colour palette leaning on " + palette + ".")
				+ " Natural light, calm, minimal composition. No text, no letters, no labels, no logos, no watermarks, no packaging print.";

		String url;
		try {
			JsonNode res = http.post().uri(FAL_URL).header("Authorization", "Key " + falKey)
					.contentType(MediaType.APPLICATION_JSON)
					.body(Map.of("prompt", prompt, "image_size", size, "num_images", 1, "safety_tolerance", "2"))
					.retrieve().body(JsonNode.class);
			url = res == null ? null : res.path("images").path(0).path("url").asText(null);
		} catch (Exception e) {
			log.warn("fal.ai visual failed for idea {}: {}", ideaId, e.getMessage());
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Couldn't make a visual. Try again.");
		}
		if (url == null || url.isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Couldn't make a visual. Try again.");
		}

		final String finalUrl = url;
		return tx.execute(s -> {
			Idea i = owned(userId, ideaId);
			i.setVisualUrl(finalUrl);
			return view(i);
		});
	}

	// ---------- helpers ----------

	private Idea owned(UUID userId, UUID ideaId) {
		return ideas.findOwned(ideaId, userId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Idea not found"));
	}

	private ResponseStatusException setNotFound() {
		return new ResponseStatusException(HttpStatus.NOT_FOUND, "Idea set not found");
	}

	private static IdeaView view(Idea i) {
		return new IdeaView(i.getId(), i.getFormat(), new LinkedHashSet<>(i.getChannels()), i.getTitle(),
				i.getDescription(), i.getInsight(), i.getStatus().name(), i.getDraftPostId(), i.getVisualUrl());
	}
}