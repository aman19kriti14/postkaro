package in.postkaro.service;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

/**
 * Turns local images + a shot list into a finished 9:16 reel with ffmpeg.
 *
 * Per shot: cover-crop → smooth camera move (zoompan on a 4K canvas so it
 * doesn't jitter) → colour grade → kinetic text overlay (slides up and fades in
 * right after the cut). Shot lengths snap to the music's beat, so every cut
 * lands on a beat. Shots are joined with hard cuts, then the music is laid
 * under.
 *
 * Plain Java, no Spring, so it can be tested on its own.
 */
public class ReelComposer {

	public static final int WIDTH = 1080;
	public static final int HEIGHT = 1920;
	public static final int FPS = 30;
	private static final int PARALLEL_SHOTS = 3;

	private static final String FONT_DIR = "/usr/share/fonts/truetype/noto/";
	private static final Color CTA_COLOR = new Color(0xC8, 0x10, 0x2E);

	/**
	 * One shot, ready to render. {@code media} is a still image (we add the camera
	 * move) or, when {@code video} is true, an AI-animated clip (used as it moves).
	 */
	public record Clip(Path media, boolean video, String camera, String text, double seconds, boolean cta) {
	}

	private final Map<String, Font> fontCache = new ConcurrentHashMap<>();

	/**
	 * @param music optional local audio file; null renders with silence
	 * @return the finished MP4 inside workDir
	 */
	public Path compose(List<Clip> clips, int bpm, String grade, Path music, Path workDir)
			throws IOException, InterruptedException {

		workDir = workDir.toAbsolutePath();
		double beat = 60.0 / Math.max(60, Math.min(160, bpm));
		List<Path> parts = new ArrayList<>();
		List<Future<?>> jobs = new ArrayList<>();
		double total = 0;

		// Shots are independent, so render them side by side
		ExecutorService pool = Executors.newFixedThreadPool(PARALLEL_SHOTS);
		try {
			for (int i = 0; i < clips.size(); i++) {
				Clip clip = clips.get(i);
				// Snap to whole beats, at least 2 beats long
				long beats = Math.max(2, Math.round(clip.seconds() / beat));
				double duration = beats * beat;
				boolean last = i == clips.size() - 1;

				Path textFile = workDir.resolve("text_" + i + ".png");
				Path out = workDir.resolve("shot_" + i + ".mp4");
				jobs.add(pool.submit(() -> {
					Path text = renderText(clip.text(), clip.cta(), textFile);
					renderShot(clip, duration, grade, text, last, out);
					return null;
				}));

				parts.add(out);
				total += duration;
			}

			for (Future<?> job : jobs) {
				try {
					job.get();
				} catch (ExecutionException e) {
					Throwable cause = e.getCause();
					throw cause instanceof IOException io ? io : new IOException("Shot render failed", cause);
				}
			}
		} finally {
			pool.shutdownNow();
		}

		Path list = workDir.resolve("parts.txt");
		StringBuilder sb = new StringBuilder();
		for (Path p : parts) {
			sb.append("file '").append(p.toAbsolutePath()).append("'\n");
		}
		Files.writeString(list, sb.toString(), StandardCharsets.UTF_8);

		Path reel = workDir.resolve("reel.mp4");
		List<String> cmd = new ArrayList<>(
				List.of("ffmpeg", "-y", "-f", "concat", "-safe", "0", "-i", list.toString()));

		String t = fmt(total);
		if (music != null) {
			cmd.addAll(List.of("-stream_loop", "-1", "-i", music.toAbsolutePath().toString(), "-map", "0:v", "-map",
					"1:a", "-af", "afade=t=in:st=0:d=0.15,afade=t=out:st=" + fmt(Math.max(0, total - 1.2)) + ":d=1.2"));
		} else {
			cmd.addAll(List.of("-f", "lavfi", "-i", "anullsrc=r=44100:cl=stereo", "-map", "0:v", "-map", "1:a"));
		}
		cmd.addAll(List.of("-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-t", t, "-movflags", "+faststart",
				reel.toString()));

		run(cmd, workDir);
		return reel;
	}

	// ---------------------------------------------------------------------
	// One shot

	private void renderShot(Clip clip, double duration, String grade, Path text, boolean last, Path out)
			throws IOException, InterruptedException {

		int frames = (int) Math.round(duration * FPS);

		StringBuilder f = new StringBuilder();
		if (clip.video()) {
			// Already moving: cover-crop to 9:16, and hold the last frame if the clip is
			// short
			f.append("[0:v]fps=").append(FPS).append(",scale=").append(WIDTH).append(":").append(HEIGHT)
					.append(":force_original_aspect_ratio=increase,crop=").append(WIDTH).append(":").append(HEIGHT)
					.append(",setsar=1,tpad=stop_mode=clone:stop_duration=").append(fmt(duration)).append(",")
					.append(gradeFilter(grade));
		} else {
			String[] zxy = cameraMove(clip.camera(), frames);
			f.append("[0:v]scale=2160:3840:force_original_aspect_ratio=increase,crop=2160:3840,setsar=1,")
					.append("zoompan=z='").append(zxy[0]).append("':x='").append(zxy[1]).append("':y='").append(zxy[2])
					.append("':d=").append(frames).append(":s=").append(WIDTH).append("x").append(HEIGHT)
					.append(":fps=").append(FPS).append(",").append(gradeFilter(grade));
		}
		if (last) {
			f.append(",fade=t=out:st=").append(fmt(duration - 0.35)).append(":d=0.35");
		}
		f.append("[bg]");

		List<String> cmd = new ArrayList<>(List.of("ffmpeg", "-y", "-i", clip.media().toAbsolutePath().toString()));

		if (text != null) {
			cmd.addAll(List.of("-loop", "1", "-framerate", String.valueOf(FPS), "-t", fmt(duration), "-i",
					text.toString()));
			double in = 0.12; // text lands just after the cut
			double rise = 0.35;
			double y = clip.cta() ? 0.58 : 0.40; // centre of the text block, as a share of height
			f.append(";[1:v]format=rgba,fade=t=in:st=").append(in).append(":d=0.25:alpha=1[txt];")
					.append("[bg][txt]overlay=x=(W-w)/2:y='H*").append(y).append("-h/2+60*pow(max(0,1-(t-").append(in)
					.append(")/").append(rise).append("),2)':shortest=1,format=yuv420p[v]");
		} else {
			f.append(";[bg]format=yuv420p[v]");
		}

		cmd.addAll(List.of("-filter_complex", f.toString(), "-map", "[v]", "-an", "-t", fmt(duration), "-r",
				String.valueOf(FPS), "-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-pix_fmt", "yuv420p",
				out.toString()));

		run(cmd, out.getParent());
	}

	/**
	 * zoompan expressions {z, x, y}. P = progress 0→1, S = smoothed (ease in/out),
	 * so moves start and stop gently instead of linearly.
	 */
	static String[] cameraMove(String camera, int frames) {
		String p = "(on/" + frames + ")";
		String s = "(" + p + "*" + p + "*(3-2*" + p + "))";
		String cx = "iw/2-(iw/zoom/2)";
		String cy = "ih/2-(ih/zoom/2)";
		String spanX = "(iw-iw/zoom)";
		String spanY = "(ih-ih/zoom)";

		return switch (camera == null ? "" : camera.toLowerCase(Locale.ROOT)) {
		case "pull_out" -> new String[] { "1.18-0.18*" + s, cx, cy };
		case "pan_left" -> new String[] { "1.15", spanX + "*(1-" + s + ")", cy };
		case "pan_right" -> new String[] { "1.15", spanX + "*" + s, cy };
		case "tilt_up" -> new String[] { "1.15", cx, spanY + "*(1-" + s + ")" };
		case "tilt_down" -> new String[] { "1.15", cx, spanY + "*" + s };
		case "orbit" ->
			new String[] { "1.1+0.08*" + s, spanX + "*(0.25+0.5*" + s + ")", spanY + "*(0.6-0.2*" + s + ")" };
		case "parallax" -> new String[] { "1.12+0.06*" + s, spanX + "*(0.7-0.4*" + s + ")", cy };
		case "whip_in" -> new String[] { "1+0.3*(1-pow(1-" + p + ",3))", cx, cy };
		case "static_zoom_punch" -> new String[] { "if(lt(on,5),1.2-0.04*on,1+0.06*" + p + ")", cx, cy };
		default -> new String[] { "1+0.18*" + s, cx, cy }; // push_in
		};
	}

	static String gradeFilter(String grade) {
		return switch (grade == null ? "" : grade) {
		case "clean_bright" -> "eq=brightness=0.03:contrast=1.05:saturation=1.08";
		case "moody_cinematic" ->
			"eq=contrast=1.15:saturation=0.85:brightness=-0.03,colorbalance=bs=0.05:rh=0.04,vignette=PI/4";
		case "vibrant_pop" -> "eq=contrast=1.1:saturation=1.35";
		case "soft_pastel" -> "eq=contrast=0.92:saturation=0.85:brightness=0.04";
		default -> "eq=contrast=1.06:saturation=1.1,colorbalance=rs=0.05:gs=0.02:bs=-0.05,vignette=PI/5"; // warm_film
		};
	}

	// ---------------------------------------------------------------------
	// On-screen text, drawn with Java2D so Indic scripts are shaped correctly

	Path renderText(String raw, boolean cta, Path out) throws IOException {
		String text = stripEmoji(raw == null ? "" : raw).trim();
		if (text.isEmpty()) {
			return null;
		}

		Font font = fontFor(text).deriveFont(Font.BOLD, 92f);
		int maxWidth = 900;
		int padX = 30;
		int padY = 16;
		int gap = 14;

		BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
		Graphics2D pg = probe.createGraphics();
		pg.setFont(font);
		FontMetrics fm = pg.getFontMetrics();
		List<String> lines = wrap(text, fm, maxWidth);
		// Balance the lines: shrink the width while the line count stays the same,
		// so we get "Nobody tells / you this" instead of an orphan word
		for (int w = maxWidth - 20; w > 200 && lines.size() > 1; w -= 20) {
			List<String> tighter = wrap(text, fm, w);
			if (tighter.size() != lines.size()) {
				break;
			}
			lines = tighter;
		}
		pg.dispose();

		int lineH = fm.getAscent() + fm.getDescent();
		int boxH = lineH + padY * 2;
		int width = 0;
		for (String line : lines) {
			width = Math.max(width, fm.stringWidth(line) + padX * 2);
		}
		int height = lines.size() * boxH + (lines.size() - 1) * gap;

		BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
		g.setFont(font);

		int y = 0;
		for (String line : lines) {
			int w = fm.stringWidth(line) + padX * 2;
			int x = (width - w) / 2;
			g.setColor(cta ? CTA_COLOR : new Color(0, 0, 0, 150));
			g.fillRoundRect(x, y, w, boxH, 26, 26);
			g.setColor(Color.WHITE);
			g.drawString(line, x + padX, y + padY + fm.getAscent());
			y += boxH + gap;
		}
		g.dispose();

		ImageIO.write(img, "png", out.toFile());
		return out;
	}

	private static List<String> wrap(String text, FontMetrics fm, int maxWidth) {
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split("\\s+")) {
			String candidate = line.isEmpty() ? word : line + " " + word;
			if (fm.stringWidth(candidate) <= maxWidth || line.isEmpty()) {
				line.setLength(0);
				line.append(candidate);
			} else {
				lines.add(line.toString());
				line.setLength(0);
				line.append(word);
			}
		}
		if (!line.isEmpty()) {
			lines.add(line.toString());
		}
		return lines;
	}

	/** Picks the Noto font for the first non-Latin script in the text. */
	private Font fontFor(String text) {
		String file = "NotoSans-Bold.ttf";
		for (int i = 0; i < text.length();) {
			int cp = text.codePointAt(i);
			String scriptFile = switch (Character.UnicodeScript.of(cp)) {
			case DEVANAGARI -> "NotoSansDevanagari-Bold.ttf";
			case TAMIL -> "NotoSansTamil-Bold.ttf";
			case TELUGU -> "NotoSansTelugu-Bold.ttf";
			case KANNADA -> "NotoSansKannada-Bold.ttf";
			case MALAYALAM -> "NotoSansMalayalam-Bold.ttf";
			case BENGALI -> "NotoSansBengali-Bold.ttf";
			case GUJARATI -> "NotoSansGujarati-Bold.ttf";
			case GURMUKHI -> "NotoSansGurmukhi-Bold.ttf";
			case ORIYA -> "NotoSansOriya-Bold.ttf";
			default -> null;
			};
			if (scriptFile != null) {
				file = scriptFile;
				break;
			}
			i += Character.charCount(cp);
		}

		return fontCache.computeIfAbsent(file, name -> {
			try {
				return Font.createFont(Font.TRUETYPE_FONT, new File(FONT_DIR + name));
			} catch (Exception e) {
				return new Font(Font.SANS_SERIF, Font.BOLD, 76);
			}
		});
	}

	/** Java2D can't draw colour emoji, so drop them from on-screen text. */
	private static String stripEmoji(String s) {
		StringBuilder sb = new StringBuilder();
		s.codePoints().filter(cp -> cp < 0x2600 || (cp > 0x27BF && cp < 0xFE00) || (cp > 0xFE0F && cp < 0x1F000))
				.forEach(sb::appendCodePoint);
		return sb.toString().replaceAll("\\s{2,}", " ");
	}

	// ---------------------------------------------------------------------

	private static String fmt(double d) {
		return String.format(Locale.ROOT, "%.3f", d);
	}

	private static void run(List<String> cmd, Path dir) throws IOException, InterruptedException {
		Path log = dir.resolve("ffmpeg_" + System.nanoTime() + ".log");
		Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true)
				.redirectOutput(log.toFile()).start();
		if (!p.waitFor(5, TimeUnit.MINUTES)) {
			p.destroyForcibly();
			throw new IOException("ffmpeg timed out");
		}
		if (p.exitValue() != 0) {
			String tail = Files.readString(log);
			tail = tail.length() > 1500 ? tail.substring(tail.length() - 1500) : tail;
			throw new IOException("ffmpeg failed: " + tail);
		}
	}
}