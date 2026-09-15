package in.postkaro.controller;

import in.postkaro.dto.response.ApiResponse;
import in.postkaro.entity.ConnectedAccount;
import in.postkaro.entity.User;
import in.postkaro.enums.SocialPlatform;
import in.postkaro.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/oauth")
@RequiredArgsConstructor
public class OAuthController {

	private final UserRepository userRepository;
	private final RestTemplate restTemplate = new RestTemplate();

	@Value("${meta.app.id}")
	private String metaAppId;

	@Value("${meta.app.secret}")
	private String metaAppSecret;

	@Value("${meta.redirect.uri}")
	private String metaRedirectUri;

	/**
	 * Frontend calls this to get the Meta OAuth URL to redirect the user to.
	 */
	@GetMapping("/meta/url")
	public ResponseEntity<ApiResponse<Map<String, String>>> getMetaOAuthUrl(@AuthenticationPrincipal User user,
			@RequestParam(defaultValue = "instagram") String platform) {

		String state = user.getId().toString() + "|" + platform;

		String url = "https://www.facebook.com/v21.0/dialog/oauth" + "?client_id=" + metaAppId + "&redirect_uri="
				+ URLEncoder.encode(metaRedirectUri, StandardCharsets.UTF_8) + "&config_id=1892512525246291" + "&state="
				+ URLEncoder.encode(state, StandardCharsets.UTF_8) + "&response_type=code";

		return ResponseEntity.ok(ApiResponse.ok(Map.of("url", url), "OK"));
	}

	/**
	 * Meta redirects here after user authorizes. Exchanges code for token, fetches
	 * profile, saves connected account.
	 */
	@GetMapping("/meta/callback")
	public ResponseEntity<String> metaCallback(@RequestParam("code") String code, @RequestParam("state") String state) {
		try {
			// Parse state
			String[] parts = state.split("\\|");
			String userId = parts[0];
			String platform = parts.length > 1 ? parts[1] : "instagram";

			// Exchange code for access token
			String tokenUrl = "https://graph.facebook.com/v21.0/oauth/access_token" + "?client_id=" + metaAppId
					+ "&redirect_uri=" + URLEncoder.encode(metaRedirectUri, StandardCharsets.UTF_8) + "&client_secret="
					+ metaAppSecret + "&code=" + code;

			Map<String, Object> tokenResponse = restTemplate.getForObject(tokenUrl, Map.class);
			String accessToken = (String) tokenResponse.get("access_token");

			// Get long-lived token
			String longLivedUrl = "https://graph.facebook.com/v21.0/oauth/access_token"
					+ "?grant_type=fb_exchange_token" + "&client_id=" + metaAppId + "&client_secret=" + metaAppSecret
					+ "&fb_exchange_token=" + accessToken;

			Map<String, Object> longLivedResponse = restTemplate.getForObject(longLivedUrl, Map.class);
			String longLivedToken = (String) longLivedResponse.get("access_token");

			// Get user profile
			Map<String, Object> profile = restTemplate.getForObject(
					"https://graph.facebook.com/v21.0/me?fields=id,name&access_token=" + longLivedToken, Map.class);

			String platformUserId = (String) profile.get("id");
			String platformName = (String) profile.get("name");

			// Get Instagram business account if platform is instagram
			String igUsername = null;
			String igUserId = null;
			if ("instagram".equals(platform)) {
				try {
					Map<String, Object> accounts = restTemplate.getForObject(
							"https://graph.facebook.com/v21.0/me/accounts?access_token=" + longLivedToken, Map.class);
					var data = (java.util.List<Map<String, Object>>) accounts.get("data");
					if (data != null && !data.isEmpty()) {
						String pageId = (String) data.get(0).get("id");
						Map<String, Object> igAccount = restTemplate.getForObject("https://graph.facebook.com/v21.0/"
								+ pageId + "?fields=instagram_business_account{id,username}&access_token="
								+ longLivedToken, Map.class);
						if (igAccount.containsKey("instagram_business_account")) {
							Map<String, Object> igBiz = (Map<String, Object>) igAccount
									.get("instagram_business_account");
							igUserId = (String) igBiz.get("id");
							igUsername = (String) igBiz.get("username");
						}
					}
				} catch (Exception e) {
					// Instagram account not found — continue with Facebook
				}
			}

			// Save connected account
			User user = userRepository.findById(java.util.UUID.fromString(userId)).orElseThrow();

			SocialPlatform socialPlatform = "instagram".equals(platform) && igUserId != null ? SocialPlatform.INSTAGRAM
					: SocialPlatform.FACEBOOK;

			String finalUserId = igUserId != null ? igUserId : platformUserId;
			String finalUsername = igUsername != null ? igUsername : platformName;

			// Check if already connected
			boolean alreadyConnected = user.getConnectedAccounts().stream()
					.anyMatch(ca -> ca.getPlatform() == socialPlatform && ca.getPlatformUserId().equals(finalUserId));

			if (!alreadyConnected) {
				ConnectedAccount account = ConnectedAccount.builder().user(user).platform(socialPlatform)
						.platformUserId(finalUserId).platformUsername("@" + finalUsername)
						.platformDisplayName(finalUsername).accessToken(longLivedToken).active(true).build();
				user.getConnectedAccounts().add(account);
				userRepository.save(user);
			}

			// Redirect back to frontend
			String frontendUrl = "http://localhost:3000/connect-accounts?connected="
					+ socialPlatform.name().toLowerCase();
			return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, frontendUrl).build();

		} catch (Exception e) {
			e.printStackTrace();
			String errorUrl = "http://localhost:3000/connect-accounts?error=connection_failed";
			return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, errorUrl).build();
		}
	}
}