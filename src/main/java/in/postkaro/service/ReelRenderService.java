package in.postkaro.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Plan → finished reel: fetches the user's photos, generates the AI shots,
 * renders with ReelComposer, and stores the MP4 on Cloudinary.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReelRenderService {

	/**
	 * Added to every AI shot: text is overlaid later, so the image must have none.
	 */
	private static final String AI_SHOT_SUFFIX = """

			Vertical 9:16 photograph for an Instagram Reel. Absolutely no text, letters, numbers,
			logos, watermarks, phone screens or user interfaces anywhere in the image. Keep the
			middle third of the frame calm and uncluttered, because bold text is placed there later.
			Real, natural, high-end photography — not an illustration, not a stock-photo look.""";

	private final FalImageClient falImageClient;
	private final MediaService mediaService;
	private final FalVideoClient falVideoClient;
	private final FalMusicClient falMusicClient;

	private final ReelComposer composer = new ReelComposer();
	private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
			.connectTimeout(Duration.ofSeconds(20)).build();

	public record RenderResult(String url, double seconds, int shots, int animatedShots, boolean music,
			String caption) {
	}

	/** How many shots in a plan need an AI image — used to price the render. */
	public static int aiShotCount(ReelPlannerService.ReelPlan plan) {
		return (int) plan.shots().stream().filter(s -> "ai".equals(s.source())).count();
	}

	/**
	 * Which shots get real AI motion in cinematic mode: the first and the last
	 * full-frame shots. Card shots (screenshots, posters) are never animated — AI
	 * motion would warp their text.
	 */
	public static List<Integer> animatedShots(ReelPlannerService.ReelPlan plan) {
		List<ReelPlannerService.Shot> shots = plan.shots();
		Integer first = null;
		Integer last = null;
		for (int i = 0; i < shots.size(); i++) {
			if (!shots.get(i).card()) {
				if (first == null) {
					first = i;
				}
				last = i;
			}
		}
		if (first == null) {
			return List.of();
		}
		return first.equals(last) ? List.of(first) : List.of(first, last);
	}

	/**
	 * @param photoUrls the same list, in the same order, that was sent to /plan
	 * @param musicUrl  the user's own track (uploaded to our storage); wins over
	 *                  autoMusic
	 * @param autoMusic compose an original track from the plan's mood and BPM
	 * @param cinematic animate the hook and payoff shots with AI image-to-video
	 */
	public RenderResult render(ReelPlannerService.ReelPlan plan, List<String> photoUrls, String musicUrl,
			boolean autoMusic, boolean cinematic) {
		Path work = null;
		ExecutorService pool = Executors.newFixedThreadPool(6);
		try {
			work = Files.createTempDirectory("reel-");
			final Path dir = work;
			List<String> photos = photoUrls == null ? List.of() : photoUrls;
			List<ReelPlannerService.Shot> shots = plan.shots();
			List<Integer> toAnimate = cinematic ? animatedShots(plan) : List.of();

			// 1. Every shot's still, as a public URL: user photos already are, AI shots get
			// generated
			List<CompletableFuture<String>> stillUrls = new ArrayList<>();
			for (int i = 0; i < shots.size(); i++) {
				ReelPlannerService.Shot shot = shots.get(i);
				final int n = i;
				if ("upload".equals(shot.source()) && shot.photoIndex() != null && shot.photoIndex() < photos.size()) {
					stillUrls.add(CompletableFuture.completedFuture(photos.get(shot.photoIndex())));
				} else {
					stillUrls.add(CompletableFuture.supplyAsync(() -> {
						List<String> urls = falImageClient.generate(shot.imagePrompt() + AI_SHOT_SUFFIX, List.of(),
								"9:16", "2K", 1);
						if (urls.isEmpty()) {
							throw new IllegalStateException("No image came back for shot " + n);
						}
						return urls.get(0);
					}, pool));
				}
			}

			// 2. Download stills (each URL once), and animate the chosen shots — all in
			// parallel
			Map<String, CompletableFuture<Path>> downloads = new HashMap<>();
			List<CompletableFuture<Path>> stillFiles = new ArrayList<>();
			Map<Integer, CompletableFuture<Path>> clipFiles = new HashMap<>();

			for (int i = 0; i < shots.size(); i++) {
				final int n = i;
				ReelPlannerService.Shot shot = shots.get(i);
				CompletableFuture<String> still = stillUrls.get(i);

				stillFiles.add(still.thenComposeAsync(url -> {
					synchronized (downloads) {
						return downloads.computeIfAbsent(url, u -> CompletableFuture
								.supplyAsync(() -> download(u, dir.resolve("still_" + downloads.size())), pool));
					}
				}, pool));

				if (toAnimate.contains(i)) {
					int clipSeconds = shot.seconds() <= 3.0 ? 3 : 5;
					clipFiles.put(i, still.thenApplyAsync(url -> {
						try {
							String clipUrl = falVideoClient.animate(url, shot.aiMotion(), clipSeconds);
							return download(clipUrl, dir.resolve("anim_" + n));
						} catch (RuntimeException e) {
							// A failed animation shouldn't sink the reel: fall back to the still + camera
							// move
							log.warn("Animation failed for shot {}, using the still instead: {}", n, e.getMessage());
							return null;
						}
					}, pool));
				}
			}

			// Music runs alongside the images and animation, so it adds no waiting
			CompletableFuture<Path> music;
			if (musicUrl != null && !musicUrl.isBlank()) {
				music = CompletableFuture.supplyAsync(() -> download(musicUrl, dir.resolve("music")), pool);
			} else if (autoMusic) {
				music = CompletableFuture.supplyAsync(() -> {
					try {
						String track = falMusicClient.compose(plan.musicMood(), plan.bpm());
						return download(track, dir.resolve("music"));
					} catch (RuntimeException e) {
						// A reel without music beats no reel at all
						log.warn("Music generation failed, rendering silent: {}", e.getMessage());
						return null;
					}
				}, pool);
			} else {
				music = CompletableFuture.completedFuture(null);
			}

			// 3. Build the clips once everything is ready
			List<ReelComposer.Clip> clips = new ArrayList<>();
			int animated = 0;
			for (int i = 0; i < shots.size(); i++) {
				ReelPlannerService.Shot shot = shots.get(i);
				boolean last = i == shots.size() - 1;
				Path clip = clipFiles.containsKey(i) ? clipFiles.get(i).join() : null;
				if (clip != null) {
					animated++;
					clips.add(
							new ReelComposer.Clip(clip, true, false, shot.camera(), shot.text(), shot.seconds(), last));
				} else {
					clips.add(new ReelComposer.Clip(stillFiles.get(i).join(), false, shot.card(), shot.camera(),
							shot.text(), shot.seconds(), last));
				}
			}

			// 4. Render
			long start = System.currentTimeMillis();
			Path track = music.join();
			Path reel = composer.compose(clips, plan.bpm(), plan.colorGrade(), track, dir);
			log.info("Rendered reel: {} shots, {} animated, in {} ms", clips.size(), animated,
					System.currentTimeMillis() - start);

			// 5. Store permanently
			String url = mediaService.storeBytes(Files.readAllBytes(reel), "video/mp4");
			double seconds = probeSeconds(reel);

			return new RenderResult(url, seconds, clips.size(), animated, track != null, plan.caption());

		} catch (CompletionException e) {
			Throwable cause = e.getCause() != null ? e.getCause() : e;
			log.error("Reel assets failed", cause);
			throw new IllegalStateException("Couldn't get all the shots ready. Please try again.", cause);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Reel rendering was interrupted.", e);
		} catch (IOException e) {
			log.error("Reel render failed", e);
			throw new IllegalStateException("Couldn't render the reel. Please try again.", e);
		} finally {
			pool.shutdownNow();
			deleteQuietly(work);
		}
	}

	// ---------------------------------------------------------------------

	/**
	 * Only fetch from our own storage and fal. The server downloads these URLs, so
	 * accepting any address would let someone make it call internal services.
	 */
	private static boolean allowed(URI uri) {
		String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
		return "https".equals(uri.getScheme()) && (host.equals("res.cloudinary.com") || host.endsWith(".fal.media")
				|| host.equals("fal.media") || host.endsWith(".fal.run"));
	}

	private Path download(String url, Path target) {
		try {
			URI uri = URI.create(url);
			if (!allowed(uri)) {
				throw new IOException("Not an allowed media URL: " + url);
			}
			HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60)).GET().build();
			HttpResponse<Path> res = http.send(req, HttpResponse.BodyHandlers.ofFile(target));
			if (res.statusCode() >= 400) {
				throw new IOException("HTTP " + res.statusCode() + " for " + url);
			}
			return target;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new CompletionException(e);
		} catch (IOException e) {
			throw new CompletionException(e);
		}
	}

	/**
	 * Actual length of the finished file (beat snapping changes it from the plan).
	 */
	private double probeSeconds(Path file) {
		try {
			Process p = new ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "format=duration", "-of",
					"default=nw=1:nk=1", file.toString()).redirectErrorStream(true).start();
			String out = new String(p.getInputStream().readAllBytes()).trim();
			p.waitFor();
			return Double.parseDouble(out);
		} catch (Exception e) {
			return 0;
		}
	}

	private static void deleteQuietly(Path dir) {
		if (dir == null) {
			return;
		}
		try (Stream<Path> walk = Files.walk(dir)) {
			walk.sorted(Comparator.reverseOrder()).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (IOException ignored) {
					// temp files; the OS cleans up eventually
				}
			});
		} catch (IOException ignored) {
			// nothing to clean
		}
	}
}