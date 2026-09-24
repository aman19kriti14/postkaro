package in.postkaro.service;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import in.postkaro.dto.request.DraftDto;
import in.postkaro.dto.request.DraftsSummary;
import in.postkaro.entity.Post;
import in.postkaro.entity.PostMedia;
import in.postkaro.enums.PostStatus;
import in.postkaro.repository.PostRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class DraftService {

	private final PostRepository posts;

	@Transactional(readOnly = true)
	public DraftsSummary list(UUID userId, String sort) {
		List<DraftDto> drafts = posts.findByUserIdAndStatus(userId, PostStatus.DRAFT).stream().map(this::toDto)
				.sorted(comparator(sort)).toList();

		return new DraftsSummary(drafts.size(), (int) drafts.stream().filter(DraftDto::readyToSchedule).count(),
				(int) drafts.stream().filter(d -> !d.hasVisual()).count(),
				(int) drafts.stream().filter(d -> !d.hasChannel()).count(),
				(int) drafts.stream().filter(DraftDto::fromAiStudio).count(), drafts);
	}

	private DraftDto toDto(Post p) {
		List<PostMedia> media = p.getMedia();
		boolean hasVisual = media != null && !media.isEmpty();
		String firstUrl = hasVisual ? media.get(0).getUrl() : null;

		String contentType;
		if (!hasVisual)
			contentType = "TEXT";
		else if (media.size() > 1)
			contentType = "CAROUSEL";
		else {
			String type = String.valueOf(media.get(0).getType());
			contentType = "VIDEO".equalsIgnoreCase(type) ? "REEL" : type;
		}

		List<String> channels = List.copyOf(p.getChannels());
		boolean hasChannel = !channels.isEmpty();
		boolean hasCaption = p.getCaption() != null && !p.getCaption().isBlank();
		boolean fromAi = p.getPrompt() != null && !p.getPrompt().isBlank();

		return new DraftDto(p.getId(), titleFrom(p.getCaption()), p.getCaption(), contentType, firstUrl,
				hasVisual ? media.size() : 0, fromAi, channels, hasVisual, hasChannel,
				hasVisual && hasChannel && hasCaption, p.getUpdatedAt());
	}

	private String titleFrom(String caption) {
		if (caption == null || caption.isBlank())
			return "Untitled draft";
		String first = caption.strip().split("\\R", 2)[0];
		return first.length() > 60 ? first.substring(0, 57) + "…" : first;
	}

	private Comparator<DraftDto> comparator(String sort) {
		return switch (sort == null ? "" : sort) {
		case "oldest" -> Comparator.comparing(DraftDto::updatedAt);
		case "ready" -> Comparator.comparing(DraftDto::readyToSchedule).reversed().thenComparing(DraftDto::updatedAt,
				Comparator.reverseOrder());
		default -> Comparator.comparing(DraftDto::updatedAt, Comparator.reverseOrder());
		};
	}
}