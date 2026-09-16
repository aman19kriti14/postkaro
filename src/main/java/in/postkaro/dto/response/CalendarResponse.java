package in.postkaro.dto.response;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record CalendarResponse(LocalDate from, LocalDate to, Summary summary, List<CalendarPost> posts,
		List<CampaignBand> campaigns, List<CadenceGap> cadenceGaps) {

	public record Summary(int total, int published, int scheduled, int needsReview, int drafts, int failed,
			int cadenceGaps) {
	}

    public record CalendarPost(
            UUID postId,
            String title,
            String captionPreview,
            String thumbnailUrl,
            String format,          // reel, carousel, post, story
            String stage,           // tease, explain, proof, convert
            String channel,         // one chip per channel
            String status,          // PUBLISHED, SCHEDULED, NEEDS_REVIEW, DRAFT, FAILED
            OffsetDateTime date,    // publishedAt if published, else scheduledAt (IST)
            UUID campaignId,
            boolean draggable       // false for PUBLISHED
    ) {}

    public record CampaignBand(
            UUID id,
            String name,
            LocalDate startDate,
            LocalDate endDate
    ) {}

	public record CadenceGap(LocalDate startDate, LocalDate endDate, int days, String channel // null = across all
																								// channels
	) {
	}
}