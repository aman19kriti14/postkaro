package in.postkaro.dto.request;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DraftDto(UUID id, String title, String caption, String contentType, String mediaUrl, int mediaCount,
		boolean fromAiStudio, List<String> channels, boolean hasVisual, boolean hasChannel, boolean readyToSchedule,
		Instant updatedAt) {
}