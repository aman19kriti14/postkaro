package in.postkaro.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import in.postkaro.dto.request.BrandProfileDtos.BrandProfileView;
import in.postkaro.entity.BrandProfile;
import in.postkaro.entity.BrandSettings;
import in.postkaro.entity.UserProfile;
import in.postkaro.repository.BrandProfileRepository;
import in.postkaro.repository.BrandSettingsRepository;
import in.postkaro.repository.UserProfileRepository;
import in.postkaro.repository.UserRepository;
import in.postkaro.service.SocialContentFetcher.SocialPost;
import in.postkaro.service.WebsiteScraper.ScrapeException;
import in.postkaro.service.WebsiteScraper.WebsiteSnapshot;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * "Learn my brand": reads the user's website + recent posts, asks the model to
 * describe the brand and suggest starter prompts, stores the result, and
 * pre-fills empty Brand settings fields.
 *
 * Runs in the background — the controller returns immediately and the UI polls
 * GET /api/v1/brand-profile.
 */
@Slf4j
@Service
public class BrandProfileService {

	private static final Duration STALE_AFTER = Duration.ofMinutes(10);
	private static final Set<String> FORMATS = Set.of("reel", "carousel", "post", "story");
	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	private final BrandProfileRepository profiles;
	private final UserRepository users;
	private final UserProfileRepository userProfiles;
	private final BrandSettingsRepository brandSettings;
	private final WebsiteScraper scraper;
	private final SocialContentFetcher social;
	private final TransactionTemplate tx;

	private final ObjectMapper json = new ObjectMapper();
	private final RestClient http;

	// background work: virtual threads, max 3 analyses at once (OpenAI + scraping)
	private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
	private final Semaphore slots = new Semaphore(3);

	@Value("${openai.api.key:}")
	private String openAiKey;

	public BrandProfileService(BrandProfileRepository profiles, UserRepository users,
			UserProfileRepository userProfiles, BrandSettingsRepository brandSettings, WebsiteScraper scraper,
			SocialContentFetcher social, TransactionTemplate tx) {
		this.profiles = profiles;
		this.users = users;
		this.userProfiles = userProfiles;
		this.brandSettings = brandSettings;
		this.scraper = scraper;
		this.social = social;
		this.tx = tx;

		SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
		f.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
		f.setReadTimeout((int) Duration.ofSeconds(90).toMillis());
		this.http = RestClient.builder().requestFactory(f).build();
	}

	@PreDestroy
	void shutdown() {
		pool.shutdownNow();
	}

	// ---------- read ----------

	public BrandProfileView get(UUID userId) {
		BrandProfile p = profiles.findByUserId(userId).orElse(null);
		if (p == null)
			return new BrandProfileView(BrandProfile.Status.IDLE.name(), null, websiteFromOnboarding(userId), 0, 0,
					null, null);

		// server restarted mid-run → don't leave the UI spinning forever
		if (p.getStatus() == BrandProfile.Status.RUNNING && p.getUpdatedAt() != null
				&& p.getUpdatedAt().isBefore(Instant.now().minus(STALE_AFTER))) {
			update(userId, x -> {
				x.setStatus(BrandProfile.Status.FAILED);
				x.setStatusMessage("That took too long. Please try again.");
			});
			p = profiles.findByUserId(userId).orElseThrow();
		}
		return view(p);
	}

	// ---------- start ----------

	/** Kicks off analysis in the background. Safe to call repeatedly. */
	public BrandProfileView start(UUID userId, String websiteOverride) {
		String site = blankToNull(websiteOverride);
		if (site != null) {
			// validate early so the user gets the error right away, not after polling
			try {
				WebsiteScraper.normalize(site);
			} catch (ScrapeException e) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
			}
			saveWebsiteOnProfile(userId, site);
		}

		boolean alreadyRunning = Boolean.TRUE.equals(tx.execute(s -> {
			BrandProfile p = profiles.findByUserId(userId).orElseGet(
					() -> profiles.save(BrandProfile.builder().user(users.getReferenceById(userId)).build()));
			if (p.getStatus() == BrandProfile.Status.RUNNING && p.getUpdatedAt() != null
					&& p.getUpdatedAt().isAfter(Instant.now().minus(STALE_AFTER))) {
				return true;
			}
			p.setStatus(BrandProfile.Status.RUNNING);
			p.setStatusMessage("Getting started…");
			return false;
		}));

		if (!alreadyRunning) {
			pool.submit(() -> {
				try {
					slots.acquire();
					try {
						run(userId);
					} finally {
						slots.release();
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			});
		}
		return get(userId);
	}

	/** Fire-and-forget trigger for onboarding / new account connections. */
	public void startQuietly(UUID userId) {
		try {
			start(userId, null);
		} catch (Exception e) {
			log.warn("Brand profile auto-start failed for {}: {}", userId, e.getMessage());
		}
	}

	// ---------- the actual work ----------

	private void run(UUID userId) {
		try {
			UserProfile up = userProfiles.findByUserId(userId).orElse(null);
			String site = up == null ? null : blankToNull(up.getWebsiteUrl());

			// 1. website
			WebsiteSnapshot web = null;
			String websiteError = null;
			if (site != null) {
				progress(userId, "Reading your website…");
				try {
					web = scraper.scrape(site);
				} catch (ScrapeException e) {
					websiteError = e.getMessage();
				} catch (Exception e) {
					log.warn("Scrape failed for {}: {}", site, e.toString());
					websiteError = "We couldn't read your website.";
				}
			}

			// 2. posts
			progress(userId, "Reading your recent posts…");
			List<SocialPost> posts = social.recentPosts(userId);

			boolean hasAbout = up != null && blankToNull(up.getDescription()) != null;
			if (web == null && posts.isEmpty() && !hasAbout) {
				String msg = websiteError != null ? websiteError + " Connect an account or fix the website address."
						: "Add your website or connect an account with some posts so we have something to learn from.";
				finish(userId, BrandProfile.Status.FAILED, msg, null, null, 0, 0, site);
				return;
			}

			// 3. model
			progress(userId, "Learning your style…");
			List<SocialPost> top = posts.stream().filter(p -> !p.text().isBlank())
					.sorted(Comparator.comparingLong(SocialPost::score).reversed()).limit(12).toList();
			ArrayNode formatStats = formatStats(posts);

			ObjectNode analysis = callOpenAi(buildPrompt(up, web, posts, top, formatStats));
			sanitize(analysis);

			// 4. facts we measured ourselves — never model-written
			ObjectNode detected = analysis.putObject("detected");
			if (web != null) {
				detected.put("logoUrl", web.logoUrl());
				detected.put("ogImage", web.ogImage());
				detected.put("websiteTitle", web.title());
				ArrayNode colors = detected.putArray("colors");
				web.colors().forEach(colors::add);
			}
			ArrayNode topPosts = analysis.putArray("topPosts");
			top.stream().limit(5).forEach(p -> {
				ObjectNode n = topPosts.addObject();
				n.put("platform", p.platform());
				n.put("format", p.format());
				n.put("text", p.text());
				n.put("likes", p.likes());
				n.put("comments", p.comments());
				n.put("shares", p.shares());
				n.put("permalink", p.permalink());
			});
			analysis.set("formatStats", formatStats);
			if (websiteError != null)
				analysis.put("websiteError", websiteError);

			finish(userId, BrandProfile.Status.READY, null, analysis, web == null ? null : web.text(),
					web == null ? 0 : web.pagesRead(), posts.size(), web == null ? site : web.url());

			// 5. pre-fill Brand settings (empty fields only)
			try {
				apply(userId, false);
			} catch (Exception e) {
				log.warn("Brand settings prefill failed for {}: {}", userId, e.getMessage());
			}
		} catch (Exception e) {
			log.error("Brand profile analysis failed for {}", userId, e);
			finish(userId, BrandProfile.Status.FAILED,
					"Something went wrong while learning your brand. Please try again.", null, null, 0, 0, null);
		}
	}

	// ---------- apply to Brand settings ----------

	/**
	 * Copies what we found into BrandSettings. overwrite=false only fills blanks,
	 * so we never undo choices the user made on the Brand tab.
	 */
	public BrandProfileView apply(UUID userId, boolean overwrite) {
		BrandProfile p = profiles.findByUserId(userId)
				.filter(x -> x.getStatus() == BrandProfile.Status.READY && x.getAnalysisJson() != null)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Brand analysis isn't ready yet"));
		JsonNode a = parse(p.getAnalysisJson());

		tx.executeWithoutResult(s -> {
			BrandSettings b = brandSettings.findByUserId(userId).orElseGet(() -> {
				BrandSettings n = BrandSettings.builder().user(users.getReferenceById(userId)).build();
				n.getLanguages().add("english");
				return brandSettings.save(n);
			});

			// set up the voice if the user never has (or asked us to overwrite)
			boolean voiceUntouched = b.getVoiceDescription() == null && b.getSamplePosts().isEmpty();
			JsonNode voice = a.path("voice");

			List<String> colors = strings(a.path("detected").path("colors")).stream()
					.filter(c -> c.matches("^#[0-9A-F]{6}$")).limit(5).toList();
			if (!colors.isEmpty() && (overwrite || b.getColors().isEmpty()))
				b.setColors(new ArrayList<>(colors));

			String logo = blankToNull(a.path("detected").path("logoUrl").asText(null));
			if (logo != null && logo.length() <= 1000 && (overwrite || b.getLogoUrl() == null))
				b.setLogoUrl(logo);

			String tone = voice.path("tone").asText("");
			if (BrandSettingsService.TONES.contains(tone) && (overwrite || voiceUntouched))
				b.setTone(tone);

			Set<String> langs = new LinkedHashSet<>(strings(a.path("languages")));
			langs.retainAll(BrandSettingsService.LANGUAGES);
			if (!langs.isEmpty() && (overwrite || voiceUntouched))
				b.setLanguages(langs);

			String desc = blankToNull(voice.path("description").asText(null));
			if (desc != null && (overwrite || b.getVoiceDescription() == null))
				b.setVoiceDescription(limit(desc, 2000));

			String use = joinLimited(strings(voice.path("wordsToUse")), 500);
			if (use != null && (overwrite || b.getWordsToUse() == null))
				b.setWordsToUse(use);

			String avoid = joinLimited(strings(voice.path("wordsToAvoid")), 500);
			if (avoid != null && (overwrite || b.getWordsToAvoid() == null))
				b.setWordsToAvoid(avoid);

			// their real best captions (not model-written) as voice samples
			List<String> samples = new ArrayList<>();
			a.path("topPosts").forEach(n -> {
				String t = n.path("text").asText("").strip();
				if (t.length() >= 40)
					samples.add(limit(t, 2200));
			});
			if (!samples.isEmpty() && (overwrite || b.getSamplePosts().isEmpty()))
				b.setSamplePosts(new ArrayList<>(samples.stream().limit(5).toList()));

			brandSettings.save(b);
		});
		return get(userId);
	}

	// ---------- for other AI prompts (AI Studio, captions) ----------

	/** Compact brand facts for idea/caption prompts. Empty when not analysed. */
	public String promptContext(UUID userId) {
		BrandProfile p = profiles.findByUserId(userId).orElse(null);
		if (p == null || p.getStatus() != BrandProfile.Status.READY || p.getAnalysisJson() == null)
			return "";
		JsonNode a = parse(p.getAnalysisJson());

		StringBuilder sb = new StringBuilder();
		line(sb, "What the business does", a.path("businessSummary").asText(null));
		list(sb, "What they sell / offer", strings(a.path("offerings")), 12);
		line(sb, "Audience", a.path("audience").asText(null));
		list(sb, "What makes them different", strings(a.path("differentiators")), 5);

		List<String> pillars = new ArrayList<>();
		a.path("contentPillars").forEach(n -> pillars.add(n.path("name").asText("")));
		list(sb, "Content pillars", pillars, 6);

		JsonNode cs = a.path("captionStyle");
		if (cs.isObject()) {
			sb.append("Caption style: ").append(cs.path("length").asText("")).append(" length, ")
					.append(cs.path("emojis").asText("")).append(" emojis. Hashtags: ")
					.append(cs.path("hashtags").asText("")).append(". CTA: ").append(cs.path("callToAction").asText(""))
					.append('\n');
		}
		list(sb, "Signature hashtags", strings(a.path("signatureHashtags")), 8);
		list(sb, "What works for them (from their real numbers)", strings(a.path("whatWorks")), 5);
		return sb.toString().trim();
	}

	// ---------- prompt ----------

	private String buildPrompt(UserProfile up, WebsiteSnapshot web, List<SocialPost> all, List<SocialPost> top,
			ArrayNode formatStats) {
		StringBuilder m = new StringBuilder();

		m.append("## Owner's own description (from signup)\n");
		if (up != null) {
			m.append("Brand name: ").append(nz(up.getBrandName())).append('\n');
			m.append("Category: ")
					.append(up.getCategory() == null ? "" : up.getCategory().name().replace('_', ' ').toLowerCase())
					.append('\n');
			m.append("About: ").append(nz(up.getDescription())).append('\n');
		}

		if (web != null) {
			m.append("\n## Their website (").append(web.url()).append(")\n");
			m.append("Title: ").append(nz(web.title())).append('\n');
			m.append("Description: ").append(nz(web.description())).append('\n');
			m.append(web.text()).append('\n');
		} else {
			m.append("\n## Website\n(none available)\n");
		}

		if (!all.isEmpty()) {
			m.append("\n## Their best-performing posts (real numbers)\n");
			for (SocialPost p : top) {
				m.append("- [").append(p.platform()).append(" · ").append(p.format()).append(" · ").append(p.likes())
						.append(" likes, ").append(p.comments()).append(" comments");
				if (p.shares() > 0)
					m.append(", ").append(p.shares()).append(" shares");
				m.append("] ").append(limit(p.text().replace('\n', ' '), 400)).append('\n');
			}

			Set<SocialPost> shown = new LinkedHashSet<>(top);
			List<SocialPost> recent = all.stream().filter(p -> !shown.contains(p) && !p.text().isBlank()).limit(8)
					.toList();
			if (!recent.isEmpty()) {
				m.append("\n## Other recent posts\n");
				recent.forEach(p -> m.append("- [").append(p.platform()).append(" · ").append(p.format()).append("] ")
						.append(limit(p.text().replace('\n', ' '), 250)).append('\n'));
			}

			m.append("\n## Averages per format (real numbers)\n");
			formatStats.forEach(n -> m.append("- ").append(n.path("format").asText()).append(": ")
					.append(n.path("posts").asInt()).append(" posts, avg ").append(n.path("avgLikes").asLong())
					.append(" likes, avg ").append(n.path("avgComments").asLong()).append(" comments\n"));
		} else {
			m.append("\n## Their posts\n(no posts available yet)\n");
		}

		LocalDate today = LocalDate.now(IST);

		return """
				You are a senior social media strategist for small Indian businesses and creators.
				Study the material below and describe this brand so an AI can write posts that sound exactly like them.
				Today is %s (India).

				%s

				Rules:
				- Use ONLY facts found in the material. Never invent products, prices, offers, discounts, awards or numbers.
				- "whatWorks": 2-4 observations backed by the real numbers above (e.g. "Reels get about 3x the comments of photo posts").
				  Return [] if there are no posts.
				- voice.tone must be exactly one of: warm, playful, informative, festive, plain.
				- languages: subset of [english, hindi, hinglish] based on how they actually write. Default ["english"].
				- voice.description: 2-3 sentences, written to a copywriter ("Sounds like…, uses…, never…").
							- starterPrompts: exactly 8 post briefs the owner will paste into a post generator.
				  * "prompt" is an INSTRUCTION, never a caption. Start with a verb (Show, Explain, Compare, Share, Announce, Make a reel where…).
				    25-60 words. Say what the post shows, the hook/angle, and what the visual should be.
				    BAD:  "Let PostKaro handle your social media posts while you focus on what you love!"
				    GOOD: "Make a reel showing a café owner typing one line into AI Studio and getting 3 ready Instagram captions in
				           Hindi and English. Hook: 'Captions in 10 seconds'. End on the scheduler calendar filling up."
				  * Every prompt must name at least one SPECIFIC product, service, feature or offer exactly as it appears in "offerings".
				  * "title": specific and concrete, names the product or result, max 8 words. No slogans.
				    BAD: "Create content effortlessly"   GOOD: "3 captions in 10 seconds with AI Studio"
				  * Use 8 DIFFERENT angles, e.g.: product demo, how-it-works, customer problem → solution, quick tip or myth-buster,
				    before/after, behind the scenes, comparison, FAQ answered. Testimonial/social proof only if real reviews appear
				    in the material. Offer/discount only if the material states one.
				  * Lean toward the formats that perform best for them (see averages). At most 1 may use an Indian festival or season
				    in the next 6 weeks, and only if it genuinely fits this business.
				  * "why": one short line citing real evidence — a page of their website ("Pricing page lists 4 plans") or a number
				    from their posts ("Reels average 3x the comments"). Never vague reasons like "users are interested".
				- format must be one of: reel, carousel, post, story.
				- No emojis in titles. Keep everything concise.

				Respond with JSON only, in this exact shape:
				{
				  "businessSummary": "2-3 sentences",
				  "offerings": ["specific product or service, with price only if stated"],
				  "audience": "who buys / follows, 1 sentence",
				  "differentiators": ["..."],
				  "contentPillars": [{"name": "2-4 words", "description": "1 sentence"}],
				  "voice": {"tone": "warm", "description": "...", "wordsToUse": ["..."], "wordsToAvoid": ["..."]},
				  "languages": ["english"],
				  "captionStyle": {"length": "short|medium|long", "emojis": "none|light|heavy", "hashtags": "how they use hashtags", "callToAction": "their usual CTA"},
				  "signatureHashtags": ["#..."],
				  "whatWorks": ["..."],
				  "starterPrompts": [{"title": "specific, max 8 words", "prompt": "Show … (instruction, 25-60 words)", "format": "reel", "pillar": "one of your contentPillars names", "why": "evidence from site or numbers"}]
				}
				"""
				.formatted(today, m.toString().trim());
	}

	private ObjectNode callOpenAi(String prompt) {
		if (openAiKey == null || openAiKey.isBlank())
			throw new IllegalStateException("OPENAI_API_KEY not set");

		Map<String, Object> body = Map.of("model", "gpt-4o-mini", "temperature", 0.4, "max_tokens", 3000,
				"response_format", Map.of("type", "json_object"), "messages",
				List.of(Map.of("role", "user", "content", prompt)));

		JsonNode res = http.post().uri("https://api.openai.com/v1/chat/completions")
				.header("Authorization", "Bearer " + openAiKey).contentType(MediaType.APPLICATION_JSON).body(body)
				.retrieve().body(JsonNode.class);
		String content = res == null ? "{}" : res.path("choices").path(0).path("message").path("content").asText("{}");
		JsonNode node = parse(content);
		if (!node.isObject())
			throw new IllegalStateException("Model returned non-object JSON");
		return (ObjectNode) node;
	}

	/** Clamp model output to values the rest of the app accepts. */
	private void sanitize(ObjectNode a) {
		ObjectNode voice = a.path("voice").isObject() ? (ObjectNode) a.get("voice") : a.putObject("voice");
		if (!BrandSettingsService.TONES.contains(voice.path("tone").asText("")))
			voice.put("tone", "warm");

		ArrayNode langs = json.createArrayNode();
		strings(a.path("languages")).stream().map(String::toLowerCase).filter(BrandSettingsService.LANGUAGES::contains)
				.distinct().forEach(langs::add);
		if (langs.isEmpty())
			langs.add("english");
		a.set("languages", langs);

		ArrayNode cleaned = json.createArrayNode();
		for (JsonNode sp : a.path("starterPrompts")) {
			String prompt = sp.path("prompt").asText("").strip();
			if (prompt.isEmpty() || !sp.isObject())
				continue;
			ObjectNode o = (ObjectNode) sp;
			String f = o.path("format").asText("post").toLowerCase();
			o.put("format", FORMATS.contains(f) ? f : "post");
			o.put("title", limit(o.path("title").asText(limit(prompt, 60)), 120));
			cleaned.add(o);
			if (cleaned.size() == 8)
				break;
		}
		a.set("starterPrompts", cleaned);
	}

	// ---------- persistence helpers ----------

	private void progress(UUID userId, String msg) {
		update(userId, p -> p.setStatusMessage(msg));
	}

	private void finish(UUID userId, BrandProfile.Status status, String message, ObjectNode analysis,
			String websiteText, int pagesRead, int postsRead, String websiteUrl) {
		update(userId, p -> {
			p.setStatus(status);
			p.setStatusMessage(message);
			if (status == BrandProfile.Status.READY) {
				p.setAnalysisJson(analysis.toString());
				p.setAnalyzedAt(Instant.now());
				p.setWebsiteText(websiteText);
				p.setWebsitePagesRead(pagesRead);
				p.setPostsRead(postsRead);
				p.setWebsiteUrl(websiteUrl);
			}
			// on FAILED keep the previous good analysis (if any) so the UI can still show
			// it
		});
	}

	private void update(UUID userId, Consumer<BrandProfile> change) {
		tx.executeWithoutResult(s -> profiles.findByUserId(userId).ifPresent(p -> {
			change.accept(p);
			profiles.save(p);
		}));
	}

	private void saveWebsiteOnProfile(UUID userId, String site) {
		tx.executeWithoutResult(s -> userProfiles.findByUserId(userId).ifPresent(up -> {
			up.setWebsiteUrl(limit(site.trim(), 500));
			userProfiles.save(up);
		}));
	}

	private String websiteFromOnboarding(UUID userId) {
		return userProfiles.findByUserId(userId).map(UserProfile::getWebsiteUrl).orElse(null);
	}

	private BrandProfileView view(BrandProfile p) {
		JsonNode analysis = p.getAnalysisJson() == null ? null : parse(p.getAnalysisJson());
		return new BrandProfileView(p.getStatus().name(), p.getStatusMessage(), p.getWebsiteUrl(),
				p.getWebsitePagesRead(), p.getPostsRead(), p.getAnalyzedAt(), analysis);
	}

	// ---------- small helpers ----------

	private ArrayNode formatStats(List<SocialPost> posts) {
		Map<String, List<SocialPost>> byFormat = posts.stream()
				.collect(Collectors.groupingBy(SocialPost::format, LinkedHashMap::new, Collectors.toList()));
		ArrayNode out = json.createArrayNode();
		byFormat.forEach((f, list) -> {
			ObjectNode n = out.addObject();
			n.put("format", f);
			n.put("posts", list.size());
			n.put("avgLikes", Math.round(list.stream().mapToLong(SocialPost::likes).average().orElse(0)));
			n.put("avgComments", Math.round(list.stream().mapToLong(SocialPost::comments).average().orElse(0)));
		});
		return out;
	}

	private JsonNode parse(String s) {
		try {
			String c = s.trim();
			int a = c.indexOf('{'), b = c.lastIndexOf('}');
			if (a >= 0 && b > a)
				c = c.substring(a, b + 1);
			return json.readTree(c);
		} catch (Exception e) {
			return json.createObjectNode();
		}
	}

	private static List<String> strings(JsonNode arr) {
		List<String> out = new ArrayList<>();
		if (arr != null && arr.isArray())
			arr.forEach(n -> {
				String t = n.asText("").strip();
				if (!t.isEmpty())
					out.add(t);
			});
		return out;
	}

	private static void line(StringBuilder sb, String label, String v) {
		if (v != null && !v.isBlank())
			sb.append(label).append(": ").append(v.strip()).append('\n');
	}

	private static void list(StringBuilder sb, String label, List<String> xs, int max) {
		if (!xs.isEmpty())
			sb.append(label).append(": ").append(String.join("; ", xs.stream().limit(max).toList())).append('\n');
	}

	private static String joinLimited(List<String> xs, int max) {
		if (xs.isEmpty())
			return null;
		StringBuilder sb = new StringBuilder();
		for (String x : xs) {
			if (sb.length() + x.length() + 2 > max)
				break;
			if (sb.length() > 0)
				sb.append(", ");
			sb.append(x);
		}
		return sb.length() == 0 ? null : sb.toString();
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s.trim();
	}

	private static String nz(String s) {
		return s == null ? "" : s;
	}

	private static String limit(String s, int max) {
		if (s == null)
			return null;
		return s.length() <= max ? s : s.substring(0, max - 1) + "…";
	}
}