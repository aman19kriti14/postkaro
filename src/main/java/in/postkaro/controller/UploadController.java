package in.postkaro.controller;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import in.postkaro.entity.User;
import in.postkaro.repository.UserRepository;
import in.postkaro.service.MediaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/v1/uploads")
@RequiredArgsConstructor
public class UploadController {

	private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
	private static final Set<String> LOGO_TYPES = Set.of("image/png", "image/jpeg", "image/webp", "image/svg+xml");
	private static final Set<String> VIDEO_TYPES = Set.of("video/mp4", "video/quicktime");

	private static final long MAX_IMAGE = 10L * 1024 * 1024; // 10 MB
	private static final long MAX_VIDEO = 25L * 1024 * 1024; // 25 MB

	private final MediaService mediaService;
	private final UserRepository users;

	/**
	 * POST /api/v1/uploads (multipart/form-data) file: the file kind: "logo" |
	 * "media" (media = Create Post uploads) → { "url": "...", "type": "image" |
	 * "video" }
	 */
	@PostMapping
	public Map<String, String> upload(Authentication auth, @RequestParam("file") MultipartFile file,
			@RequestParam(defaultValue = "media") String kind) {

		UUID userId = userId(auth); // must be logged in

		if (file == null || file.isEmpty())
			throw bad("Pick a file to upload");

		String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
		boolean isLogo = "logo".equals(kind);
		boolean isVideo = VIDEO_TYPES.contains(type);

		if (isLogo) {
			if (!LOGO_TYPES.contains(type))
				throw bad("Logo must be PNG, JPG, WebP or SVG");
		} else if (!IMAGE_TYPES.contains(type) && !isVideo) {
			throw bad("Upload a JPG, PNG, WebP image or an MP4 video");
		}

		long limit = isVideo ? MAX_VIDEO : MAX_IMAGE;
		if (file.getSize() > limit) {
			throw bad(isVideo ? "Videos must be under 25 MB" : "Images must be under 10 MB");
		}

		try {
			String url = mediaService.storeBytes(file.getBytes(), type);
			if (url == null || url.isBlank())
				throw new IllegalStateException("No URL returned");
			log.info("Upload ({}) by {}: {} bytes → {}", kind, userId, file.getSize(), url);
			return Map.of("url", url, "type", isVideo ? "video" : "image");
		} catch (ResponseStatusException e) {
			throw e;
		} catch (Exception e) {
			log.warn("Upload failed for {}: {}", userId, e.getMessage());
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Upload failed. Try again.");
		}
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

	private static ResponseStatusException bad(String m) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
	}
}