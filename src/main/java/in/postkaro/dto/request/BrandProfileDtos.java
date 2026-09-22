package in.postkaro.dto.request;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.constraints.Size;

public final class BrandProfileDtos {

	private BrandProfileDtos() {
	}

	/** POST /analyze — websiteUrl optional; falls back to the onboarding one. */
	public record AnalyzeRequest(@Size(max = 500) String websiteUrl) {
	}

	/** POST /apply — overwrite=false only fills Brand settings fields that are empty. */
	public record ApplyRequest(boolean overwrite) {
	}

	/**
	 * status: IDLE | RUNNING | READY | FAILED. Poll every ~2s while RUNNING.
	 * analysis is null until READY. Shape:
	 *
	 * <pre>
	 * businessSummary, offerings[], audience, differentiators[],
	 * contentPillars[{name, description}],
	 * voice{tone, description, wordsToUse[], wordsToAvoid[]}, languages[],
	 * captionStyle{length, emojis, hashtags, callToAction}, signatureHashtags[],
	 * whatWorks[], starterPrompts[{title, prompt, format, pillar, why}],
	 * detected{logoUrl, colors[], ogImage, websiteTitle},
	 * topPosts[{platform, format, text, likes, comments, shares, permalink}],
	 * formatStats[{format, posts, avgLikes, avgComments}], websiteError
	 * </pre>
	 */
	public record BrandProfileView(String status, String statusMessage, String websiteUrl, int websitePagesRead,
			int postsRead, Instant analyzedAt, JsonNode analysis) {
	}
}
