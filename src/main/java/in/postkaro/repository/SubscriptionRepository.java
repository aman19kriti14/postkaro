package in.postkaro.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import in.postkaro.entity.Subscription;
import jakarta.persistence.LockModeType;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

	Optional<Subscription> findByUserId(UUID userId);

	/** Row lock so two parallel AI calls can't spend the same credits. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from Subscription s where s.user.id = :userId")
	Optional<Subscription> findByUserIdForUpdate(@Param("userId") UUID userId);

	/** Paid subscriptions due for a monthly credit reset. */
	List<Subscription> findByStatusAndCurrentPeriodEndBefore(Subscription.Status status, Instant now);

	/** Trials that have run out and still need to be marked EXPIRED. */
	List<Subscription> findByStatusAndTrialEndsAtBefore(Subscription.Status status, Instant now);
}