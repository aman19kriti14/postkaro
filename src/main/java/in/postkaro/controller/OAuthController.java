package in.postkaro.controller;

import in.postkaro.dto.response.ApiResponse;
import in.postkaro.entity.ConnectedAccount;
import in.postkaro.entity.User;
import in.postkaro.enums.SocialPlatform;
import in.postkaro.repository.ConnectedAccountRepository;
import in.postkaro.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/oauth")
@RequiredArgsConstructor
public class OAuthController {

	private final UserRepository userRepository;
	private final ConnectedAccountRepository connectedAccountRepository;
	private final RestClient restClient = RestClient.create();

	@Value("${meta.ig.app.id}")
	private String fbAppId;

	@Value("${meta.ig.app.secret}")
	private String fbAppSecret;

	@Value("${instagram.app.id}")
	private String igAppId;

	@Value("${instagram.app.secret}")
	private String igAppSecret;

	private static final String CALLBACK_URI = "https://postkaro-production.up.railway.app/api/v1/oauth/callback";
	@Value("${app.frontend.url:http://localhost:3000}")
	private String frontendUrl;
	private static final String GRAPH_API_VERSION = "v21.0";

	// ─── Get OAuth URL ───────────────────────────────────────────────

	@GetMapping("/meta/url")
	public ResponseEntity<ApiResponse<Map<String, String>>> getOAuthUrl(@AuthenticationPrincipal User user,
			@RequestParam(defaultValue = "instagram") String platform) {

		String state = user.getId().toString() + "|" + platform;
		String url;

		if ("instagram".equals(platform)) {
			// String scopes =
			// "instagram_business_basic,instagram_business_manage_messages,instagram_business_manage_comments,instagram_business_content_publish";
			String scopes = "instagram_business_basic,instagram_business_manage_messages,instagram_business_manage_comments,instagram_business_content_publish,instagram_business_manage_insights";
			url = "https://www.instagram.com/oauth/authorize" + "?client_id=" + igAppId + "&redirect_uri="
					+ URLEncoder.encode(CALLBACK_URI, StandardCharsets.UTF_8) + "&state="
					+ URLEncoder.encode(state, StandardCharsets.UTF_8) + "&scope=" + scopes + "&response_type=code";
		} else {
			// Facebook
			url = "https://www.facebook.com/" + GRAPH_API_VERSION + "/dialog/oauth" + "?client_id=" + fbAppId
					+ "&redirect_uri=" + URLEncoder.encode(CALLBACK_URI, StandardCharsets.UTF_8) + "&state="
					+ URLEncoder.encode(state, StandardCharsets.UTF_8)
					+ "&scope=pages_show_list,pages_read_engagement,pages_manage_posts,publish_video"
					+ "&response_type=code";
		}

		return ResponseEntity.ok(ApiResponse.ok(Map.of("url", url), "OK"));
	}

	// ─── Unified Callback ────────────────────────────────────────────

	@GetMapping("/callback")
	public ResponseEntity<Void> callback(@RequestParam("code") String code, @RequestParam("state") String state) {
		try {
			String[] parts = state.split("\\|");
			String userId = parts[0];
			String platform = parts.length > 1 ? parts[1] : "instagram";

			if ("instagram".equals(platform)) {
				handleInstagramCallback(code, userId);
			} else {
				handleFacebookCallback(code, userId);
			}

			String redirectUrl = frontendUrl + "/connect-accounts?connected=" + platform;
			return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, redirectUrl).build();

		} catch (Exception e) {
			System.out.println("OAUTH CALLBACK ERROR: " + e.getMessage());
			e.printStackTrace();
			return ResponseEntity.status(HttpStatus.FOUND)
					.header(HttpHeaders.LOCATION, frontendUrl + "/connect-accounts?error=connection_failed").build();
		}
	}

	// ─── Instagram Token Exchange ────────────────────────────────────

	@SuppressWarnings("unchecked")
	private void handleInstagramCallback(String code, String userId) {
		// Step 1: Exchange code for short-lived token (POST with form data)
		URI tokenUri = URI.create("https://api.instagram.com/oauth/access_token");
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("client_id", igAppId);
		form.add("client_secret", igAppSecret);
		form.add("grant_type", "authorization_code");
		form.add("redirect_uri", CALLBACK_URI);
		form.add("code", code);

		Map<String, Object> tokenResponse = restClient.post().uri(tokenUri)
				.contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(Map.class);

		String shortToken = (String) tokenResponse.get("access_token");
		String igUserId = String.valueOf(tokenResponse.get("user_id"));

		// Step 2: Exchange for long-lived token
		URI longTokenUri = URI.create("https://graph.instagram.com/access_token"
				+ "?grant_type=ig_exchange_token&client_secret=" + igAppSecret + "&access_token=" + shortToken);

		Map<String, Object> longLived = restClient.get().uri(longTokenUri).retrieve().body(Map.class);
		String longToken = (String) longLived.get("access_token");

		// Step 3: Get username
		URI profileUri = URI
				.create("https://graph.instagram.com/" + igUserId + "?fields=username&access_token=" + longToken);

		Map<String, Object> profile = restClient.get().uri(profileUri).retrieve().body(Map.class);
		String username = (String) profile.get("username");

		// Step 4: Save
		saveConnectedAccount(userId, SocialPlatform.INSTAGRAM, igUserId, username, longToken);
	}

	// ─── Facebook Token Exchange ─────────────────────────────────────

	@SuppressWarnings("unchecked")
	private void handleFacebookCallback(String code, String userId) {
		// Step 1: Exchange code for token
		URI tokenUri = URI.create("https://graph.facebook.com/" + GRAPH_API_VERSION + "/oauth/access_token"
				+ "?client_id=" + fbAppId + "&client_secret=" + fbAppSecret + "&redirect_uri="
				+ URLEncoder.encode(CALLBACK_URI, StandardCharsets.UTF_8) + "&code=" + code);

		Map<String, Object> tokenResponse = restClient.get().uri(tokenUri).retrieve().body(Map.class);
		String shortToken = (String) tokenResponse.get("access_token");

		// Step 2: Long-lived token
		URI longTokenUri = URI.create("https://graph.facebook.com/" + GRAPH_API_VERSION + "/oauth/access_token"
				+ "?grant_type=fb_exchange_token" + "&client_id=" + fbAppId + "&client_secret=" + fbAppSecret
				+ "&fb_exchange_token=" + shortToken);

		Map<String, Object> longLived = restClient.get().uri(longTokenUri).retrieve().body(Map.class);
		String longUserToken = (String) longLived.get("access_token");

		// Step 3: Get pages
		URI pagesUri = URI.create("https://graph.facebook.com/" + GRAPH_API_VERSION
				+ "/me/accounts?fields=id,name,access_token&access_token=" + longUserToken);

		Map<String, Object> pagesResponse = restClient.get().uri(pagesUri).retrieve().body(Map.class);

		List<Map<String, Object>> pages = (List<Map<String, Object>>) pagesResponse.get("data");

		if (pages != null && !pages.isEmpty()) {
			Map<String, Object> page = pages.get(0);
			String pageId = (String) page.get("id");
			String pageName = (String) page.get("name");
			String pageToken = (String) page.get("access_token");

			saveConnectedAccount(userId, SocialPlatform.FACEBOOK, pageId, pageName, pageToken);

			// Auto-connect linked Instagram Business Account
			try {
				URI igUri = URI.create("https://graph.facebook.com/" + GRAPH_API_VERSION + "/" + pageId
						+ "?fields=instagram_business_account{id,username}&access_token=" + pageToken);

				Map<String, Object> igResponse = restClient.get().uri(igUri).retrieve().body(Map.class);

				Map<String, Object> igBiz = (Map<String, Object>) igResponse.get("instagram_business_account");
				if (igBiz != null) {
					String igBizId = (String) igBiz.get("id");
					String igUsername = (String) igBiz.get("username");
					saveConnectedAccount(userId, SocialPlatform.INSTAGRAM, igBizId,
							igUsername != null ? igUsername : "Instagram", pageToken);
				}
			} catch (Exception e) {
				System.out.println("Instagram not linked to Facebook page: " + e.getMessage());
			}
		}
	}

	// ─── Save Helper ─────────────────────────────────────────────────

	private void saveConnectedAccount(String userId, SocialPlatform platform, String platformUserId, String displayName,
			String token) {
		UUID userUuid = UUID.fromString(userId);
		User user = userRepository.findById(userUuid).orElseThrow();

		boolean exists = connectedAccountRepository.existsByUserIdAndPlatformAndPlatformUserId(userUuid, platform,
				platformUserId);

		if (!exists) {
			ConnectedAccount account = ConnectedAccount.builder().user(user).platform(platform)
					.platformUserId(platformUserId).platformUsername("@" + displayName).platformDisplayName(displayName)
					.accessToken(token).active(true).build();
			connectedAccountRepository.save(account);
			System.out.println("SAVED: " + platform + " account @" + displayName + " for user " + userId);
		} else {
			ConnectedAccount account = connectedAccountRepository
					.findByUserIdAndPlatformAndPlatformUserId(userUuid, platform, platformUserId)
					.orElseThrow(() -> new RuntimeException("Connected account not found"));

			account.setAccessToken(token);
			account.setActive(true);
			account.setPlatformUsername("@" + displayName);
			account.setPlatformDisplayName(displayName);

			connectedAccountRepository.save(account);

			System.out.println("UPDATED: " + platform + " account @" + displayName + " for user " + userId);
		}
	}
}