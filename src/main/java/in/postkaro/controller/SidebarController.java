package in.postkaro.controller;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import in.postkaro.dto.response.ApiResponse;
import in.postkaro.entity.User;
import in.postkaro.enums.PostStatus;
import in.postkaro.repository.CampaignRepository;
import in.postkaro.repository.PostRepository;
import in.postkaro.service.CalendarService;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/sidebar")
@RequiredArgsConstructor
public class SidebarController {

	private final CalendarService calendarService;
	private final PostRepository postRepository;
	private final CampaignRepository campaignRepository;

	// GET /api/v1/sidebar/counts
	@GetMapping("/counts")
	public ResponseEntity<ApiResponse<SidebarCounts>> counts(@AuthenticationPrincipal User user) {
		UUID userId = user.getId();
		LocalDate today = LocalDate.now(CalendarService.IST);

		int calendar = calendarService.getMonth(userId, YearMonth.from(today), null, 3).summary().total();
		long campaigns = campaignRepository.countByUserIdAndEndsOnGreaterThanEqual(userId, today);
		long drafts = postRepository.countByUserIdAndStatus(userId, PostStatus.DRAFT);
		long needsReview = postRepository.countByUserIdAndStatus(userId, PostStatus.NEEDS_REVIEW);

		return ResponseEntity.ok(ApiResponse.ok(new SidebarCounts(calendar, campaigns, drafts, needsReview), "OK"));
	}

	public record SidebarCounts(int calendar, long campaigns, long drafts, long needsReview) {
	}
}