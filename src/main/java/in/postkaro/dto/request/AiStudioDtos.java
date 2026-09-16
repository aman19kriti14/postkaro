package in.postkaro.dto.request;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public final class AiStudioDtos {

	private AiStudioDtos() {
	}

	// ---------- requests ----------

	public record GenerateRequest(@NotBlank @Size(max = 1000) String brief, Set<String> sources, // brand_voice,
																									// product_list,
																									// past_top_posts,
																									// uploaded_photos
			@NotEmpty Set<String> formats, // reel, carousel, post, story
			@NotEmpty Set<String> channels) {
	} // instagram, facebook, linkedin, youtube, x

	public record SaveSetRequest(@Size(max = 80) String name) {
	}

	// ---------- responses ----------

	public record IdeaView(UUID id, String format, Set<String> channels, String title, String description,
			String insight, // null when there's no real data to back one
			String status, // ACTIVE, DISMISSED, DRAFTED
			UUID draftPostId, String visualUrl) {
	}

	public record IdeaSetView(UUID id, String name, String brief, Set<String> sources, Set<String> formats,
			Set<String> channels, boolean saved, long keptCount, long dismissedCount, List<IdeaView> ideas, // includes
																											// dismissed;
																											// the UI
																											// hides
																											// them
			Instant createdAt) {
	}

	public record SavedSetSummary(UUID id, String name, long ideaCount, Instant updatedAt) {
	}
}