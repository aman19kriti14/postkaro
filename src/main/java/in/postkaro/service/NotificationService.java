package in.postkaro.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import in.postkaro.entity.Subscription;
import in.postkaro.entity.UpgradeRequest;
import in.postkaro.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Emails that go to you, not to users. Sent through Resend's HTTP API because
 * Railway blocks outbound SMTP ports. Runs on a background thread so a slow or
 * failing send never holds up a request.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

	private static final String RESEND_URL = "https://api.resend.com/emails";

	private final ObjectMapper objectMapper;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "notify");
		t.setDaemon(true);
		return t;
	});

	@Value("${app.resend.api-key:}")
	private String apiKey;

	@Value("${app.notify.from:}")
	private String from;

	@Value("${app.notify.to:}")
	private String to;

	/** Someone tapped "Request upgrade" or "Buy credits". */
	public void upgradeRequested(UpgradeRequest req, User user, Subscription sub) {
		String what = req.getKind() == UpgradeRequest.Kind.PLAN
				? "wants the " + req.getPlan().name() + " plan (Rs " + req.getPlan().getPriceInr() + "/mo)"
				: "wants " + req.getCredits() + " top-up credits";

		String subject = "PostKaro: " + user.getFullName() + " " + what;

		StringBuilder body = new StringBuilder();
		body.append(user.getFullName()).append(" (").append(user.getEmail()).append(")\n");
		if (req.getPhone() != null && !req.getPhone().isBlank()) {
			body.append("Phone: ").append(req.getPhone()).append("\n");
		}
		body.append("\nRequest: ").append(what).append("\n");
		if (req.getMessage() != null && !req.getMessage().isBlank()) {
			body.append("Their note: ").append(req.getMessage()).append("\n");
		}
		body.append("\nCurrent plan: ").append(sub.getPlan().name()).append(" (").append(sub.getStatus().name())
				.append(")\n");
		body.append("Credits left: ").append(sub.totalCredits()).append("\n");
		body.append("Signed up: ").append(user.getCreatedAt()).append("\n");
		body.append("\nTo activate:\n");
		body.append("curl -X POST https://postkaro-production.up.railway.app/api/v1/admin/billing/")
				.append(req.getKind() == UpgradeRequest.Kind.PLAN ? "activate" : "topup").append(" \\\n");
		body.append("  -H \"Authorization: Bearer <your-jwt>\" \\\n");
		body.append("  -H \"X-Admin-Token: <token>\" \\\n");
		body.append("  -H \"Content-Type: application/json\" \\\n");
		if (req.getKind() == UpgradeRequest.Kind.PLAN) {
			body.append("  -d '{\"email\":\"").append(user.getEmail()).append("\",\"plan\":\"")
					.append(req.getPlan().name()).append("\"}'\n");
		} else {
			body.append("  -d '{\"email\":\"").append(user.getEmail()).append("\",\"credits\":")
					.append(req.getCredits()).append("}'\n");
		}

		send(subject, body.toString());
	}

	/** Send an already-composed email to the owner address. */
	public void sendRaw(String subject, String body) {
		send(subject, body);
	}

	/**
	 * General-purpose: use this for OTPs, password resets, anything user-facing.
	 */
	public void sendTo(String recipient, String subject, String body) {
		dispatch(List.of(recipient), subject, body);
	}

	// ---------------------------------------------------------------------

	private void send(String subject, String text) {
		if (to == null || to.isBlank()) {
			log.warn("No notify address set, skipping: {}", subject);
			return;
		}
		dispatch(Arrays.stream(to.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList(), subject, text);
	}

	private void dispatch(List<String> recipients, String subject, String text) {
		if (apiKey == null || apiKey.isBlank()) {
			log.warn("Resend key not set, skipping: {}", subject);
			return;
		}

		pool.submit(() -> {
			try {
				String payload = objectMapper
						.writeValueAsString(Map.of("from", from, "to", recipients, "subject", subject, "text", text));

				HttpRequest request = HttpRequest.newBuilder(URI.create(RESEND_URL))
						.header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
						.timeout(Duration.ofSeconds(15)).POST(HttpRequest.BodyPublishers.ofString(payload)).build();

				HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

				if (response.statusCode() >= 200 && response.statusCode() < 300) {
					log.info("Sent notification: {}", subject);
				} else {
					log.error("Resend rejected the email ({}): {}", response.statusCode(), response.body());
				}
			} catch (Exception e) {
				log.error("Notification failed: {}", subject, e);
			}
		});
	}
}