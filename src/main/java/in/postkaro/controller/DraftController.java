package in.postkaro.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import in.postkaro.dto.request.DraftsSummary;
import in.postkaro.entity.User;
import in.postkaro.service.DraftService;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/drafts")
@RequiredArgsConstructor
public class DraftController {

	private final DraftService drafts;

	@GetMapping
	public DraftsSummary list(@AuthenticationPrincipal User user, @RequestParam(defaultValue = "recent") String sort) {
		return drafts.list(user.getId(), sort);
	}
}