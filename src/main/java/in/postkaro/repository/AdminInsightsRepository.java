package in.postkaro.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import in.postkaro.dto.response.AdminInsightsDtos.Lead;
import in.postkaro.dto.response.AdminInsightsDtos.PendingRequest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;

@Repository
public class AdminInsightsRepository {

	@PersistenceContext
	private EntityManager em;

	private static final String LEAD_COLUMNS = """
			u.id, u.full_name, u.email, p.phone, u.created_at
			""";

	public long totalUsers() {
		return ((Number) em.createNativeQuery("select count(*) from users").getSingleResult()).longValue();
	}

	/** Signed up but never connected a live channel. */
	public List<Lead> noAccountConnected(int limit) {
		Query q = em.createNativeQuery("""
				select %s, u.onboarding_complete
				from users u
				left join user_profiles p on p.user_id = u.id
				where not exists (
					select 1 from connected_accounts c where c.user_id = u.id and c.active = true
				)
				order by u.created_at desc
				limit :lim
				""".formatted(LEAD_COLUMNS)).setParameter("lim", limit);

		return q.getResultList().stream().map(r -> {
			Object[] row = (Object[]) r;
			boolean onboarded = Boolean.TRUE.equals(row[5]);
			return lead(row, onboarded ? "Finished onboarding, no channel connected" : "Onboarding not finished");
		}).toList();
	}

	/** Connected a channel but never made a single post. */
	public List<Lead> connectedButNoPost(int limit) {
		Query q = em.createNativeQuery("""
				select %s, (
					select string_agg(distinct c2.platform, ', ')
					from connected_accounts c2 where c2.user_id = u.id and c2.active = true
				)
				from users u
				left join user_profiles p on p.user_id = u.id
				where exists (
					select 1 from connected_accounts c where c.user_id = u.id and c.active = true
				)
				and not exists (select 1 from posts po where po.user_id = u.id)
				order by u.created_at desc
				limit :lim
				""".formatted(LEAD_COLUMNS)).setParameter("lim", limit);

		return q.getResultList().stream().map(r -> {
			Object[] row = (Object[]) r;
			return lead(row, "Connected " + str(row[5]) + ", no posts yet");
		}).toList();
	}

	/** Made posts, but nothing ever went live. Includes the last failure reason. */
	public List<Lead> postedButNothingPublished(int limit) {
		Query q = em.createNativeQuery("""
				select %s,
					(select count(*) from posts a where a.user_id = u.id),
					(select count(*) from posts f where f.user_id = u.id and f.status = 'FAILED'),
					(select f2.publish_error from posts f2
						where f2.user_id = u.id and f2.publish_error is not null
						order by f2.updated_at desc limit 1)
				from users u
				left join user_profiles p on p.user_id = u.id
				where exists (select 1 from posts po where po.user_id = u.id)
				and not exists (
					select 1 from posts pub where pub.user_id = u.id and pub.status = 'PUBLISHED'
				)
				order by u.created_at desc
				limit :lim
				""".formatted(LEAD_COLUMNS)).setParameter("lim", limit);

		return q.getResultList().stream().map(r -> {
			Object[] row = (Object[]) r;
			long total = num(row[5]);
			long failed = num(row[6]);
			String error = str(row[7]);

			String detail = total + " post(s), none published";
			if (failed > 0) {
				detail += ", " + failed + " failed";
			}
			if (error != null && !error.isBlank()) {
				detail += " — last error: " + error;
			}
			return lead(row, detail);
		}).toList();
	}

	/** Trials ending within the given number of hours. */
	public List<Lead> trialEndingSoon(int withinHours, int limit) {
		Query q = em.createNativeQuery("""
				select %s, s.trial_ends_at, s.monthly_credits + s.topup_credits
				from users u
				join subscriptions s on s.user_id = u.id
				left join user_profiles p on p.user_id = u.id
				where s.status = 'TRIALING'
				and s.trial_ends_at between now() and now() + (:hrs || ' hours')::interval
				order by s.trial_ends_at asc
				limit :lim
				""".formatted(LEAD_COLUMNS)).setParameter("hrs", String.valueOf(withinHours)).setParameter("lim",
				limit);

		return q.getResultList().stream().map(r -> {
			Object[] row = (Object[]) r;
			Instant endsAt = instant(row[5]);
			long hoursLeft = endsAt == null ? 0
					: Math.max(0, java.time.Duration.between(Instant.now(), endsAt).toHours());
			return lead(row, "Trial ends in " + hoursLeft + "h, " + num(row[6]) + " credits left");
		}).toList();
	}

	/** Everyone waiting on you to activate a plan or add credits. */
	public List<PendingRequest> pendingRequests(int limit) {
		Query q = em.createNativeQuery("""
				select r.id, u.full_name, u.email, coalesce(r.phone, p.phone),
					r.kind, r.plan, r.credits, r.message, r.created_at
				from upgrade_requests r
				join users u on u.id = r.user_id
				left join user_profiles p on p.user_id = u.id
				where r.status = 'PENDING'
				order by r.created_at desc
				limit :lim
				""").setParameter("lim", limit);

		return q.getResultList().stream().map(r -> {
			Object[] row = (Object[]) r;
			return new PendingRequest(uuid(row[0]), str(row[1]), str(row[2]), str(row[3]), str(row[4]), str(row[5]),
					(int) num(row[6]), str(row[7]), instant(row[8]));
		}).toList();
	}

	// ---------------------------------------------------------------------

	private Lead lead(Object[] row, String detail) {
		return new Lead(uuid(row[0]), str(row[1]), str(row[2]), str(row[3]), instant(row[4]), detail);
	}

	private UUID uuid(Object o) {
		return o == null ? null : (o instanceof UUID u ? u : UUID.fromString(o.toString()));
	}

	private String str(Object o) {
		return o == null ? null : o.toString();
	}

	private long num(Object o) {
		return o instanceof Number n ? n.longValue() : 0L;
	}

	private Instant instant(Object o) {
		if (o instanceof Instant i) {
			return i;
		}
		if (o instanceof Timestamp t) {
			return t.toInstant();
		}
		if (o instanceof OffsetDateTime odt) {
			return odt.toInstant();
		}
		return null;
	}
}