package in.postkaro.dto.request;

import java.util.List;

public record DraftsSummary(int total, int almostReady, int noVisual, int noChannel, int fromAiStudio,
		List<DraftDto> drafts) {
}