package in.postkaro.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

import in.postkaro.dto.response.ApiResponse;
import in.postkaro.entity.User;
import in.postkaro.enums.CreditAction;
import in.postkaro.enums.PlanTier;
import in.postkaro.service.BrandProfileService;
import in.postkaro.service.BrandSettingsService;
import in.postkaro.service.CreditService;
import in.postkaro.service.ReelPlannerService;
import in.postkaro.service.ReelRenderService;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/reels")
@RequiredArgsConstructor
public class ReelController {

	/** 5 units x 5 credits = 25 credits per shot animated with AI video. */
	private static final int ANIMATED_SHOT_UNITS = 5;

	private final ReelPlannerService planner;
	private final ReelRenderService renderer;
	private final CreditService creditService;
	private final BrandSettingsService brandSettings;
	private final BrandProfileService brandProfile;
	private final ObjectMapper objectMapper;

	/**
	 * Plans a reel from a brief and up to 5 photos. Body: { prompt, photoUrls:
	 * [..], language: "English", tone: "warm" }. Returns the shot list — nothing is
	 * rendered yet.
	 */
	@SuppressWarnings("unchecked")
	@PostMapping("/plan")
	public ResponseEntity<ApiResponse<ReelPlannerService.ReelPlan>> plan(@AuthenticationPrincipal User user,
			@RequestBody Map<String, Object> body) {

		String prompt = (String) body.get("prompt");
		if (prompt == null || prompt.isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tell us what the reel is about.");
		}

		List<String> photoUrls = body.get("photoUrls") instanceof List<?> l
				? l.stream().filter(String.class::isInstance).map(String.class::cast).toList()
				: List.of();
		String language = (String) body.getOrDefault("language", "English");
		String tone = (String) body.getOrDefault("tone", "warm");

		String brief = buildBrief(user, prompt.trim());

		ReelPlannerService.ReelPlan plan = creditService.charge(user.getId(), CreditAction.REEL_PLAN, 1,
				() -> planner.plan(brief, photoUrls, language, tone));

		return ResponseEntity.ok(ApiResponse.ok(plan, "Reel planned."));
	}

	/**
	 * Renders a plan into an MP4. Body: { plan: <the plan from /plan>, photoUrls:
	 * [same list, same order as /plan], musicUrl: optional, quality: "quick" |
	 * "cinematic" }. Quick takes ~30-60 s; cinematic animates the hook and payoff
	 * shots with AI video and takes ~2-3 min.
	 */
	@PostMapping("/render")
	public ResponseEntity<ApiResponse<ReelRenderService.RenderResult>> render(@AuthenticationPrincipal User user,
			@RequestBody Map<String, Object> body) {

		ReelPlannerService.ReelPlan plan;
		try {
			plan = objectMapper.convertValue(body.get("plan"), ReelPlannerService.ReelPlan.class);
		} catch (IllegalArgumentException e) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That reel plan couldn't be read.");
		}
		if (plan == null || plan.shots() == null || plan.shots().size() < 2) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Plan the reel first.");
		}
		if (plan.shots().size() > 8) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A reel can have at most 8 shots.");
		}

		List<String> photoUrls = body.get("photoUrls") instanceof List<?> l
				? l.stream().filter(String.class::isInstance).map(String.class::cast).toList()
				: List.of();
		String musicUrl = (String) body.get("musicUrl");
		boolean cinematic = "cinematic".equalsIgnoreCase((String) body.getOrDefault("quality", "quick"));

		// Cinematic (real AI motion) is a Growth-and-above feature. Trial users can
		// try it too — their 100 credits cover about one, which is the point.
		if (cinematic) {
			PlanTier tier = creditService.getOrCreate(user.getId()).getPlan();
			if (tier == PlanTier.STARTER) {
				throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
						"Cinematic reels with real AI motion are on the Growth plan (Rs 2,999/month). Upgrade to make them.");
			}
		}

		// Credits, in REEL_RENDER units (5 credits each):
		// 1 for the render, 1 per AI-created image, 5 per animated shot
		final ReelPlannerService.ReelPlan finalPlan = plan;
		int units = 1 + ReelRenderService.aiShotCount(finalPlan)
				+ (cinematic ? ReelRenderService.animatedShots(finalPlan).size() * ANIMATED_SHOT_UNITS : 0);

		ReelRenderService.RenderResult result = creditService.charge(user.getId(), CreditAction.REEL_RENDER, units,
				() -> renderer.render(finalPlan, photoUrls, musicUrl, cinematic));

		return ResponseEntity.ok(ApiResponse.ok(result, "Reel ready."));
	}

	/** The brief plus the saved brand voice and brand facts, same as captions. */
	private String buildBrief(User user, String prompt) {
		String brief = prompt;

		String voice = brandSettings.promptContext(user.getId());
		if (!voice.isBlank())
			brief += "\n\nBrand voice (follow strictly):\n" + voice;

		String facts = brandProfile.promptContext(user.getId());
		if (!facts.isBlank())
			brief += "\n\nAbout the brand (use real details from here, never invent prices or offers):\n" + facts;

		return brief;
	}
}