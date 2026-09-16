package in.postkaro.controller;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonFormat;

import in.postkaro.dto.response.ApiResponse;
import in.postkaro.dto.response.CalendarResponse;
import in.postkaro.entity.User;
import in.postkaro.service.CalendarService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/calendar")
@RequiredArgsConstructor
public class CalendarController {

	private final CalendarService calendarService;

	// GET /api/v1/calendar?month=2026-09&channel=instagram&minGapDays=3
	@GetMapping
	public ResponseEntity<ApiResponse<CalendarResponse>> getMonth(@AuthenticationPrincipal User user,
			@RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
			@RequestParam(required = false) String channel, @RequestParam(defaultValue = "3") int minGapDays) {

		YearMonth target = month != null ? month : YearMonth.now(CalendarService.IST);
		CalendarResponse data = calendarService.getMonth(user.getId(), target, channel, Math.max(1, minGapDays));
		return ResponseEntity.ok(ApiResponse.ok(data, "OK"));
	}

	// PATCH /api/v1/calendar/posts/{id}/reschedule { "date": "2026-09-18", "time":
	// "11:30" }
	@PatchMapping("/posts/{id}/reschedule")
	public ResponseEntity<Void> reschedule(@AuthenticationPrincipal User user, @PathVariable UUID id,
			@Valid @RequestBody RescheduleRequest body) {

		calendarService.reschedule(user.getId(), id, body.date(), body.time());
		return ResponseEntity.noContent().build();
	}

	// POST /api/v1/calendar/posts/{id}/approve
	@PostMapping("/posts/{id}/approve")
	public ResponseEntity<Void> approve(@AuthenticationPrincipal User user, @PathVariable UUID id) {
		calendarService.approve(user.getId(), id);
		return ResponseEntity.noContent().build();
	}

	public record RescheduleRequest(@NotNull LocalDate date, @JsonFormat(pattern = "HH:mm") LocalTime time // optional
	) {
	}
}