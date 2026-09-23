package in.postkaro.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import in.postkaro.entity.EmailOtp;

@Repository
public interface EmailOtpRepository extends JpaRepository<EmailOtp, UUID> {

	/** The newest unused code for this user and purpose. */
	Optional<EmailOtp> findFirstByUserIdAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(UUID userId,
			EmailOtp.Purpose purpose);

	/** Invalidate older codes when a new one is issued. */
	@Modifying
	@Query("""
			update EmailOtp o set o.consumedAt = :now
			where o.user.id = :userId and o.purpose = :purpose and o.consumedAt is null
			""")
	void consumeAllFor(@Param("userId") UUID userId, @Param("purpose") EmailOtp.Purpose purpose,
			@Param("now") Instant now);

	/** Housekeeping: drop expired rows. */
	@Modifying
	@Query("delete from EmailOtp o where o.expiresAt < :cutoff")
	int deleteExpired(@Param("cutoff") Instant cutoff);
}