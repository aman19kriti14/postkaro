package in.postkaro.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import in.postkaro.dto.request.BrandDtos.BrandUpdate;
import in.postkaro.dto.request.BrandDtos.BrandView;
import in.postkaro.entity.BrandSettings;
import in.postkaro.entity.User;
import in.postkaro.repository.BrandSettingsRepository;
import in.postkaro.repository.UserRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class BrandSettingsService {

	public static final List<String> TONES = List.of("warm", "playful", "informative", "festive", "plain");
	public static final List<String> LANGUAGES = List.of("english", "hindi", "hinglish");
	public static final List<String> FONTS = List.of("Cormorant Garamond", "Playfair Display", "Lora", "Inter",
			"DM Sans", "Poppins", "Merriweather", "Libre Baskerville");

	private final BrandSettingsRepository repo;
	private final UserRepository users;

	// ---------- read ----------

	@Transactional
	public BrandView get(UUID userId) {
		return view(getOrCreate(userId), userId);
	}

	// ---------- save ----------

	@Transactional
	public BrandView update(UUID userId, BrandUpdate in) {
		BrandSettings b = getOrCreate(userId);

		b.setLogoUrl(blankToNull(in.logoUrl()));

		b.setColors(in.colors() == null ? new ArrayList<>()
				: in.colors().stream().map(String::toUpperCase).distinct().limit(5)
						.collect(Collectors.toCollection(ArrayList::new)));

		b.setHeadingFont(font(in.headingFont()));
		b.setBodyFont(font(in.bodyFont()));

		String tone = in.tone() == null ? "warm" : in.tone().trim().toLowerCase();
		if (!TONES.contains(tone))
			throw bad("Unknown tone");
		b.setTone(tone);

		Set<String> langs = new LinkedHashSet<>();
		if (in.languages() != null) {
			for (String l : in.languages()) {
				String x = l == null ? "" : l.trim().toLowerCase();
				if (!LANGUAGES.contains(x))
					throw bad("Unknown language: " + l);
				langs.add(x);
			}
		}
		if (langs.isEmpty())
			langs.add("english");
		b.setLanguages(langs);

		b.setVoiceDescription(blankToNull(in.voiceDescription()));
		b.setWordsToUse(cleanList(in.wordsToUse()));
		b.setWordsToAvoid(cleanList(in.wordsToAvoid()));

		b.setSamplePosts(in.samplePosts() == null ? new ArrayList<>()
				: in.samplePosts().stream().map(String::trim).filter(s -> !s.isEmpty()).limit(5)
						.collect(Collectors.toCollection(ArrayList::new)));

		repo.saveAndFlush(b);
		return view(b, userId);
	}

	// ---------- for AI prompts ----------

	/**
	 * A plain-text block describing the brand voice, ready to drop into an AI
	 * prompt. Empty string when nothing useful is set.
	 */
	@Transactional(readOnly = true)
	public String promptContext(UUID userId) {
		BrandSettings b = repo.findByUserId(userId).orElse(null);
		if (b == null)
			return "";

		StringBuilder sb = new StringBuilder();
		sb.append("Tone: ").append(b.getTone()).append('\n');
		if (!b.getLanguages().isEmpty()) {
			sb.append("Write in: ").append(String.join(", ", b.getLanguages()))
					.append(" (match the language of the brief when unsure)\n");
		}
		if (b.getVoiceDescription() != null) {
			sb.append("How the brand sounds: ").append(b.getVoiceDescription()).append('\n');
		}
		if (b.getWordsToUse() != null) {
			sb.append("Words the brand likes: ").append(b.getWordsToUse()).append('\n');
		}
		if (b.getWordsToAvoid() != null) {
			sb.append("Never use these words: ").append(b.getWordsToAvoid()).append('\n');
		}
		if (!b.getSamplePosts().isEmpty()) {
			sb.append("Approved sample posts (match this voice closely, don't copy them):\n");
			for (String s : b.getSamplePosts())
				sb.append("- ").append(s).append('\n');
		}
		return sb.toString().trim();
	}

	/**
	 * Colour palette hint for image generation, e.g. "#C8102E, #121212". Empty if
	 * none.
	 */
	@Transactional(readOnly = true)
	public String paletteHint(UUID userId) {
		return repo.findByUserId(userId).map(b -> String.join(", ", b.getColors())).orElse("");
	}

	// ---------- helpers ----------

	private BrandSettings getOrCreate(UUID userId) {
		return repo.findByUserId(userId).orElseGet(() -> {
			User user = users.getReferenceById(userId);
			BrandSettings b = BrandSettings.builder().user(user).build();
			b.getLanguages().add("english");
			return repo.save(b);
		});
	}

	private BrandView view(BrandSettings b, UUID userId) {
		return new BrandView(workspaceName(userId), b.getLogoUrl(), List.copyOf(b.getColors()), b.getHeadingFont(),
				b.getBodyFont(), b.getTone(), List.copyOf(b.getLanguages()), b.getVoiceDescription(), b.getWordsToUse(),
				b.getWordsToAvoid(), List.copyOf(b.getSamplePosts()), b.getUpdatedAt());
	}

	private String workspaceName(UUID userId) {
		return users.findById(userId).map(u -> {
			// ⚠️ ADAPT if the brand name lives on a different field
			if (u.getProfile() != null && u.getProfile().getBrandName() != null
					&& !u.getProfile().getBrandName().isBlank()) {
				return u.getProfile().getBrandName();
			}
			return u.getFullName();
		}).orElse("");
	}

	private static String font(String f) {
		if (f == null || f.isBlank())
			return null;
		if (!FONTS.contains(f))
			throw bad("Unknown font: " + f);
		return f;
	}

	/** "blend,counter , estate" -> "blend, counter, estate" */
	private static String cleanList(String s) {
		if (s == null)
			return null;
		String out = Arrays.stream(s.split(",")).map(String::trim).filter(w -> !w.isEmpty()).distinct()
				.collect(Collectors.joining(", "));
		return out.isEmpty() ? null : out;
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s.trim();
	}

	private static ResponseStatusException bad(String m) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
	}
}