package in.postkaro.controller;

import java.time.Duration;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import in.postkaro.entity.PostMedia;
import in.postkaro.repository.PostMediaRepository;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/media")
@RequiredArgsConstructor
public class MediaProxyController {

	private final PostMediaRepository postMediaRepository;

	private final RestClient restClient = RestClient.create();

	@GetMapping("/{mediaId}")
	public ResponseEntity<byte[]> getMedia(@PathVariable UUID mediaId) {

		PostMedia media = postMediaRepository.findById(mediaId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Media not found"));

		byte[] bytes = restClient.get().uri(media.getUrl()).retrieve().body(byte[].class);

		if (bytes == null || bytes.length == 0) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Media unavailable");
		}

		MediaType contentType;

		if ("video".equalsIgnoreCase(media.getType())) {
			contentType = MediaType.valueOf("video/mp4");
		} else {
			contentType = MediaType.IMAGE_JPEG;
		}

		return ResponseEntity.ok().contentType(contentType)
				.cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic()).body(bytes);
	}
}