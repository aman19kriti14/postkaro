package in.postkaro.controller;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import in.postkaro.dto.request.AiStudioDtos.GenerateRequest;
import in.postkaro.dto.request.AiStudioDtos.IdeaSetView;
import in.postkaro.dto.request.AiStudioDtos.IdeaView;
import in.postkaro.dto.request.AiStudioDtos.SaveSetRequest;
import in.postkaro.dto.request.AiStudioDtos.SavedSetSummary;
import in.postkaro.entity.User;
import in.postkaro.enums.CreditAction;
import in.postkaro.repository.IdeaSetRepository;
import in.postkaro.repository.UserRepository;
import in.postkaro.service.AiStudioService;
import in.postkaro.service.CreditService;
import in.postkaro.service.IdeaActionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/ai-studio")
@RequiredArgsConstructor
public class AiStudioController {

	private final AiStudioService studio;
	private final IdeaActionService actions;
	private final IdeaSetRepository sets;
	private final UserRepository users;
	private final CreditService creditService;

	// ---------- sets ----------

	// POST /api/v1/ai-studio/generate → 2 credits
	@PostMapping("/generate")
	public IdeaSetView generate(Authentication auth, @Valid @RequestBody GenerateRequest req) {
		UUID uid = userId(auth);
		return creditService.charge(uid, CreditAction.IDEA_SET, 1, () -> studio.generate(uid, req));
	}

	// GET /api/v1/ai-studio/latest → 204 when the user has never generated
	@GetMapping("/latest")
	public ResponseEntity<IdeaSetView> latest(Authentication auth) {
		IdeaSetView v = studio.latest(userId(auth));
		return v == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(v);
	}

	// GET /api/v1/ai-studio/sets → sidebar "Saved idea sets"
	@GetMapping("/sets")
	public List<SavedSetSummary> savedSets(Authentication auth) {
		return sets.savedSummaries(userId(auth), PageRequest.of(0, 10)).stream()
				.map(r -> new SavedSetSummary((UUID) r[0], (String) r[1], ((Number) r[2]).longValue(), (Instant) r[3]))
				.toList();
	}

	// GET /api/v1/ai-studio/sets/{id}
	@GetMapping("/sets/{id}")
	public IdeaSetView get(Authentication auth, @PathVariable UUID id) {
		return studio.get(userId(auth), id);
	}

	// POST /api/v1/ai-studio/sets/{id}/save body: { "name": "This week · rain" }
	// (optional)
	@PostMapping("/sets/{id}/save")
	public IdeaSetView save(Authentication auth, @PathVariable UUID id,
			@RequestBody(required = false) SaveSetRequest req) {
		return actions.save(userId(auth), id, req == null ? null : req.name());
	}

	// POST /api/v1/ai-studio/sets/{id}/restore
	@PostMapping("/sets/{id}/restore")
	public IdeaSetView restore(Authentication auth, @PathVariable UUID id) {
		return actions.restoreDismissed(userId(auth), id);
	}

	// ---------- ideas ----------

	// POST /api/v1/ai-studio/ideas/{id}/dismiss
	@PostMapping("/ideas/{id}/dismiss")
	public IdeaView dismiss(Authentication auth, @PathVariable UUID id) {
		return actions.dismiss(userId(auth), id);
	}

	// POST /api/v1/ai-studio/ideas/{id}/draft → { "postId": "..." }
	@PostMapping("/ideas/{id}/draft")
	public Map<String, UUID> draft(Authentication auth, @PathVariable UUID id) {
		return Map.of("postId", actions.draft(userId(auth), id));
	}

	// POST /api/v1/ai-studio/ideas/{id}/visual → 5 credits (one image)
	@PostMapping("/ideas/{id}/visual")
	public IdeaView visual(Authentication auth, @PathVariable UUID id) {
		UUID uid = userId(auth);
		return creditService.charge(uid, CreditAction.IMAGE, 1, () -> actions.makeVisual(uid, id));
	}

	// ---------- helpers ----------

	private UUID userId(Authentication auth) {
		if (auth == null || !auth.isAuthenticated()) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		}
		if (auth.getPrincipal() instanceof User u) {
			return u.getId();
		}
		return users.findByEmail(auth.getName()).map(User::getId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
	}
}