package in.postkaro.service;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.JsonNode;

import in.postkaro.entity.ConnectedAccount;
import in.postkaro.enums.SocialPlatform;
import in.postkaro.repository.ConnectedAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Pulls a user's recent posts from their connected accounts via the official
 * APIs (never scraping — that breaks platform terms and gets accounts
 * flagged). Currently: Instagram + Facebook Pages.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SocialContentFetcher {

	public record SocialPost(String platform, String account, String format, String text, long likes, long comments,
			long shares, Instant postedAt, String permalink) {

		/** Comments and shares signal more than likes. */
		public long score() {
			return likes + comments * 3 + shares * 5;
		}
	}

	private static final String GRAPH_VERSION = "v21.0";
	private static final int PER_ACCOUNT = 40;

	private final ConnectedAccountRepository accounts;
	private final RestClient http = RestClient.create();

	/** Recent posts across all active IG + FB accounts, newest first. */
	public List<SocialPost> recentPosts(UUID userId) {
		List<SocialPost> out = new ArrayList<>();

		for (ConnectedAccount a : accounts.findByUserIdAndPlatform(userId, SocialPlatform.INSTAGRAM)) {
			if (a.isActive() && a.getAccessToken() != null)
				safe(() -> out.addAll(instagram(a)), a);
		}
		for (ConnectedAccount a : accounts.findByUserIdAndPlatform(userId, SocialPlatform.FACEBOOK)) {
			if (a.isActive() && a.getAccessToken() != null)
				safe(() -> out.addAll(facebook(a)), a);
		}

		out.sort(Comparator.comparing(SocialPost::postedAt, Comparator.nullsLast(Comparator.reverseOrder())));
		return out;
	}

	// ---------- Instagram ----------

	private List<SocialPost> instagram(ConnectedAccount a) {
		// Instagram-login tokens (IGAA…) live on graph.instagram.com; tokens from the
		// Facebook-page flow (EAA…) must go through graph.facebook.com
		String host = a.getAccessToken().startsWith("IG") ? "https://graph.instagram.com"
				: "https://graph.facebook.com";

		URI uri = UriComponentsBuilder.fromHttpUrl(host + "/" + GRAPH_VERSION + "/" + a.getPlatformUserId() + "/media")
				.queryParam("fields",
						"caption,media_type,media_product_type,timestamp,like_count,comments_count,permalink")
				.queryParam("limit", PER_ACCOUNT).queryParam("access_token", a.getAccessToken()).build().toUri();

		JsonNode res = http.get().uri(uri).retrieve().body(JsonNode.class);
		List<SocialPost> out = new ArrayList<>();
		if (res == null)
			return out;

		for (JsonNode m : res.path("data")) {
			String caption = m.path("caption").asText("").strip();
			String type = m.path("media_type").asText("");
			String product = m.path("media_product_type").asText("");
			String format = "REELS".equals(product) ? "reel"
					: "CAROUSEL_ALBUM".equals(type) ? "carousel" : "VIDEO".equals(type) ? "video" : "post";

			out.add(new SocialPost("instagram", a.getPlatformUsername(), format, caption, m.path("like_count").asLong(0),
					m.path("comments_count").asLong(0), 0, parseTime(m.path("timestamp").asText(null)),
					m.path("permalink").asText(null)));
		}
		return out;
	}

	// ---------- Facebook Page ----------

	private List<SocialPost> facebook(ConnectedAccount a) {
		URI uri = UriComponentsBuilder
				.fromHttpUrl("https://graph.facebook.com/" + GRAPH_VERSION + "/" + a.getPlatformUserId() + "/posts")
				.queryParam("fields",
						"message,created_time,permalink_url,shares,reactions.summary(total_count).limit(0),comments.summary(total_count).limit(0)")
				.queryParam("limit", PER_ACCOUNT).queryParam("access_token", a.getAccessToken()).build().toUri();

		JsonNode res = http.get().uri(uri).retrieve().body(JsonNode.class);
		List<SocialPost> out = new ArrayList<>();
		if (res == null)
			return out;

		for (JsonNode p : res.path("data")) {
			String msg = p.path("message").asText("").strip();
			if (msg.isEmpty())
				continue; // photo-only / shared posts tell us nothing about voice
			out.add(new SocialPost("facebook", a.getPlatformDisplayName(), "post", msg,
					p.path("reactions").path("summary").path("total_count").asLong(0),
					p.path("comments").path("summary").path("total_count").asLong(0),
					p.path("shares").path("count").asLong(0), parseTime(p.path("created_time").asText(null)),
					p.path("permalink_url").asText(null)));
		}
		return out;
	}

	// ---------- helpers ----------

	private void safe(Runnable r, ConnectedAccount a) {
		try {
			r.run();
		} catch (Exception e) {
			// one broken token shouldn't kill the whole analysis
			log.warn("Couldn't read posts for {} account {}: {}", a.getPlatform(), a.getId(), e.getMessage());
		}
	}

	private static Instant parseTime(String s) {
		if (s == null || s.isBlank())
			return null;
		try {
			// IG: 2026-09-01T10:15:00+0000 — FB uses the same shape
			return java.time.OffsetDateTime
					.parse(s, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxx")).toInstant();
		} catch (Exception e) {
			try {
				return Instant.parse(s);
			} catch (Exception e2) {
				return null;
			}
		}
	}
}
