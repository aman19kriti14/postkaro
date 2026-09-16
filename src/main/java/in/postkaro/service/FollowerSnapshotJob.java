package in.postkaro.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;

import in.postkaro.entity.AccountSnapshot;
import in.postkaro.repository.AccountSnapshotRepository;
import in.postkaro.repository.ConnectedAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Saves each active Instagram account's follower count once per IST day. Runs
 * every 6 hours; the first run of the day creates the row, later runs update
 * it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FollowerSnapshotJob {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
	private static final String IG_GRAPH = "https://graph.instagram.com/v21.0";

	private final ConnectedAccountRepository accounts;
	private final AccountSnapshotRepository snapshots;
	private final RestClient http = RestClient.create();

	@Scheduled(fixedDelay = 6 * 60 * 60 * 1000L, initialDelay = 90_000L)
	public void run() {
		LocalDate today = LocalDate.now(IST);
		List<Object[]> rows = accounts.activeInstagramForSnapshot();
		int ok = 0;

		for (Object[] r : rows) {
			UUID accountId = (UUID) r[0];
			UUID userId = (UUID) r[1];
			String igUserId = (String) r[2];
			String token = (String) r[3];
			if (igUserId == null || token == null || token.isBlank())
				continue;

			try {
				// followers_count only needs instagram_business_basic
				JsonNode res = http.get()
						.uri(IG_GRAPH + "/{id}?fields=followers_count&access_token={t}", igUserId, token).retrieve()
						.body(JsonNode.class);
				if (res == null || !res.has("followers_count"))
					continue;

				long followers = res.path("followers_count").asLong(0);

				AccountSnapshot s = snapshots.findByConnectedAccountIdAndSnapshotDate(accountId, today)
						.orElseGet(() -> AccountSnapshot.builder().userId(userId).connectedAccountId(accountId)
								.channel("instagram").snapshotDate(today).build());
				s.setFollowers(followers);
				snapshots.save(s);
				ok++;
			} catch (Exception e) {
				log.warn("Follower snapshot failed for account {}: {}", accountId, e.getMessage());
			}
		}

		log.info("Follower snapshots: {}/{} accounts saved for {}", ok, rows.size(), today);
	}
}