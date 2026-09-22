package in.postkaro.repository;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import in.postkaro.entity.CreditTransaction;

@Repository
public interface CreditTransactionRepository extends JpaRepository<CreditTransaction, UUID> {

	Page<CreditTransaction> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

	/** Prevents refunding the same spend twice. */
	boolean existsByRefTransactionId(UUID refTransactionId);

	/** Credits used since a given time, for the "X used this month" figure. */
	@Query("""
			select coalesce(sum(-t.amount), 0) from CreditTransaction t
			where t.user.id = :userId and t.amount < 0 and t.createdAt >= :since
			""")
	int sumSpentSince(@Param("userId") UUID userId, @Param("since") Instant since);
}