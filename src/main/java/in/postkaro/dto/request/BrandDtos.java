package in.postkaro.dto.request;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class BrandDtos {

	private BrandDtos() {
	}

	/** What the Brand tab shows. */
	public record BrandView(String workspaceName, // "Chai Patti Co." (from the onboarding profile)
			String logoUrl, List<String> colors, String headingFont, String bodyFont, String tone,
			List<String> languages, String voiceDescription, String wordsToUse, String wordsToAvoid,
			List<String> samplePosts, Instant updatedAt) {
	}

	/** Full replace of the Brand tab on "Save changes". */
	public record BrandUpdate(@Size(max = 1000) String logoUrl,
			@Size(max = 5) List<@Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "Colours must look like #C8102E") String> colors,
			@Size(max = 60) String headingFont, @Size(max = 60) String bodyFont, String tone, List<String> languages,
			@Size(max = 2000) String voiceDescription, @Size(max = 500) String wordsToUse,
			@Size(max = 500) String wordsToAvoid, @Size(max = 5) List<@Size(max = 2200) String> samplePosts) {
	}
}