package in.postkaro.controller;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.annotation.JsonFormat;

import in.postkaro.dto.response.CalendarResponse;
import in.postkaro.entity.User;
import in.postkaro.repository.UserRepository;
import in.postkaro.service.CalendarService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/calendar")
@RequiredArgsConstructor
public class CalendarController {

	private final CalendarService calendarService;
	private final UserRepository userRepository;

	// GET /api/v1/calendar?month=2026-09&channel=instagram&minGapDays=3
	@GetMapping
	public CalendarResponse getMonth(Authentication auth,
			@RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
			@RequestParam(required = false) String channel, @RequestParam(defaultValue = "3") int minGapDays) {

		YearMonth target = month != null ? month : YearMonth.now(CalendarService.IST);
		return calendarService.getMonth(userId(auth), target, channel, Math.max(1, minGapDays));
	}

	// PATCH /api/v1/calendar/posts/{id}/reschedule { "date": "2026-09-18", "time":
	// "11:30" }
	@PatchMapping("/posts/{id}/reschedule")
	public ResponseEntity<Void> reschedule(Authentication auth, @PathVariable UUID id,
			@Valid @RequestBody RescheduleRequest body) {

		calendarService.reschedule(userId(auth), id, body.date(), body.time());
		return ResponseEntity.noContent().build();
	}

	// POST /api/v1/calendar/posts/{id}/approve
	@PostMapping("/posts/{id}/approve")
	public ResponseEntity<Void> approve(Authentication auth, @PathVariable UUID id) {
		calendarService.approve(userId(auth), id);
		return ResponseEntity.noContent().build();
	}

	// Works whether the JWT filter stores the User entity or just the email
	private UUID userId(Authentication auth) {
		if (auth == null || !auth.isAuthenticated()) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not logged in");
		}
		if (auth.getPrincipal() instanceof User user) {
			return user.getId();
		}
		return userRepository.findByEmail(auth.getName()).map(User::getId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
	}

	public record RescheduleRequest(@NotNull LocalDate date, @JsonFormat(pattern = "HH:mm") LocalTime time // optional
	) {
	}
}