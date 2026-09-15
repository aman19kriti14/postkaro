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
import java.util.UUID;

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

	// Hardcoded — env var was unreliable
	private static final String META_REDIRECT_URI = "https://postkaro-production.up.railway.app/api/v1/oauth/meta/callback";
	private static final String FRONTEND_URL = "http://localhost:3000";

	@GetMapping("/meta/url")
	public ResponseEntity<ApiResponse<Map<String, String>>> getMetaOAuthUrl(@AuthenticationPrincipal User user,
			@RequestParam(defaultValue = "instagram") String platform) {

		String state = user.getId().toString() + "|" + platform;

		String url = "https://www.facebook.com/v21.0/dialog/oauth" + "?client_id=" + metaAppId + "&redirect_uri="
				+ URLEncoder.encode(META_REDIRECT_URI, StandardCharsets.UTF_8) + "&config_id=1892512525246291"
				+ "&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8) + "&response_type=code";

		return ResponseEntity.ok(ApiResponse.ok(Map.of("url", url), "OK"));
	}

	@GetMapping("/meta/callback")
	public ResponseEntity<String> metaCallback(@RequestParam("code") String code, @RequestParam("state") String state) {
		try {
			// Parse state
			String[] parts = state.split("\\|");
			String userId = parts[0];
			String platform = parts.length > 1 ? parts[1] : "instagram";

			// Step 1: Exchange code for short-lived access token
			String tokenUrl = "https://graph.facebook.com/v21.0/oauth/access_token" + "?client_id=" + metaAppId
					+ "&redirect_uri=" + URLEncoder.encode(META_REDIRECT_URI, StandardCharsets.UTF_8)
					+ "&client_secret=" + metaAppSecret + "&code=" + code;

			Map<String, Object> tokenResponse = restTemplate.getForObject(tokenUrl, Map.class);
			if (tokenResponse == null || !tokenResponse.containsKey("access_token")) {
				throw new RuntimeException("No access token in response");
			}
			String accessToken = (String) tokenResponse.get("access_token");

			// Step 2: Exchange for long-lived token
			String longLivedToken = accessToken;
			try {
				String longLivedUrl = "https://graph.facebook.com/v21.0/oauth/access_token"
						+ "?grant_type=fb_exchange_token" + "&client_id=" + metaAppId + "&client_secret="
						+ metaAppSecret + "&fb_exchange_token=" + accessToken;

				Map<String, Object> longLivedResponse = restTemplate.getForObject(longLivedUrl, Map.class);
				if (longLivedResponse != null && longLivedResponse.containsKey("access_token")) {
					longLivedToken = (String) longLivedResponse.get("access_token");
				}
			} catch (Exception e) {
				System.out.println("Long-lived token exchange failed, using short-lived: " + e.getMessage());
			}

			// Step 3: Get user profile
			Map<String, Object> profile = restTemplate.getForObject(
					"https://graph.facebook.com/v21.0/me?fields=id,name&access_token=" + longLivedToken, Map.class);

			String platformUserId = (String) profile.get("id");
			String platformName = (String) profile.get("name");

			// Step 4: Save connected account
			User user = userRepository.findById(UUID.fromString(userId)).orElseThrow();

			SocialPlatform socialPlatform = "instagram".equals(platform) ? SocialPlatform.INSTAGRAM
					: SocialPlatform.FACEBOOK;

			// Check if already connected
			boolean alreadyConnected = user.getConnectedAccounts().stream().anyMatch(
					ca -> ca.getPlatform() == socialPlatform && ca.getPlatformUserId().equals(platformUserId));

			if (!alreadyConnected) {
				ConnectedAccount account = ConnectedAccount.builder().user(user).platform(socialPlatform)
						.platformUserId(platformUserId).platformUsername("@" + platformName)
						.platformDisplayName(platformName).accessToken(longLivedToken).active(true).build();
				user.getConnectedAccounts().add(account);
				userRepository.save(user);
			}

			// Redirect back to frontend with success
			String redirectUrl = FRONTEND_URL + "/connect-accounts?connected=" + socialPlatform.name().toLowerCase()
					+ "&username=" + URLEncoder.encode("@" + platformName, StandardCharsets.UTF_8);
			return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, redirectUrl).build();

		} catch (Exception e) {
			System.out.println("OAuth callback error: " + e.getMessage());
			e.printStackTrace();
			String errorUrl = FRONTEND_URL + "/connect-accounts?error=connection_failed";
			return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, errorUrl).build();
		}
	}
}