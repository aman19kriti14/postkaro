package in.postkaro.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import in.postkaro.entity.AccountSnapshot;

public interface AccountSnapshotRepository extends JpaRepository<AccountSnapshot, UUID> {

	Optional<AccountSnapshot> findByConnectedAccountIdAndSnapshotDate(UUID accountId, LocalDate date);

	// Earliest and latest snapshot per account within the range: [accountId,
	// channel, date, followers]
	@Query("""
			SELECT s.connectedAccountId, s.channel, s.snapshotDate, s.followers
			FROM AccountSnapshot s
			WHERE s.userId = :userId
			  AND s.snapshotDate >= :from AND s.snapshotDate <= :to
			ORDER BY s.connectedAccountId, s.snapshotDate
			""")
	List<Object[]> inRange(@Param("userId") UUID userId, @Param("from") LocalDate from, @Param("to") LocalDate to);
}