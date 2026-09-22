package in.postkaro.service;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import in.postkaro.entity.Subscription;
import in.postkaro.entity.UpgradeRequest;
import in.postkaro.entity.User;
import lombok.extern.slf4j.Slf4j;

/**
 * Emails that go to you, not to users. Sent on a background thread so a slow or
 * broken SMTP server never holds up a request.
 */
@Slf4j
@Service
public class NotificationService {

	private final ObjectProvider<JavaMailSender> mailSender;
	private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "notify");
		t.setDaemon(true);
		return t;
	});

	@Value("${app.notify.from:}")
	private String from;

	@Value("${app.notify.to:}")
	private String to;

	public NotificationService(ObjectProvider<JavaMailSender> mailSender) {
		this.mailSender = mailSender;
	}

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

	/**
	 * Two days before a trial runs out, so you can reach them while they're still
	 * active.
	 */

	private void send(String subject, String text) {
		JavaMailSender sender = mailSender.getIfAvailable();
		if (sender == null || to == null || to.isBlank()) {
			log.warn("Mail not configured, skipping notification: {}", subject);
			return;
		}

		pool.submit(() -> {
			try {
				SimpleMailMessage msg = new SimpleMailMessage();
				msg.setFrom(from);
				msg.setTo(Arrays.stream(to.split(",")).map(String::trim).toArray(String[]::new));
				msg.setSubject(subject);
				msg.setText(text);
				sender.send(msg);
				log.info("Sent notification: {}", subject);
			} catch (Exception e) {
				log.error("Notification failed: {}", subject, e);
			}
		});
	}

	/** Send an already-composed email to the owner address. */
	public void sendRaw(String subject, String body) {
		send(subject, body);
	}
}