package in.postkaro.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import in.postkaro.entity.Idea;

public interface IdeaRepository extends JpaRepository<Idea, UUID> {

	// Ownership check goes through the set's user
	@Query("""
			select i from Idea i
			join fetch i.ideaSet s
			where i.id = :id and s.user.id = :userId
			""")
	Optional<Idea> findOwned(@Param("id") UUID id, @Param("userId") UUID userId);

	// "Restore N dismissed"
	@Modifying
	@Query("""
			update Idea i set i.status = in.postkaro.entity.Idea.Status.ACTIVE
			where i.ideaSet.id = :setId and i.status = in.postkaro.entity.Idea.Status.DISMISSED
			""")
	int restoreDismissed(@Param("setId") UUID setId);

	// Sidebar badge: active ideas across all saved sets
	@Query("""
			select count(i) from Idea i
			where i.ideaSet.user.id = :userId
			  and i.ideaSet.saved = true
			  and i.status = in.postkaro.entity.Idea.Status.ACTIVE
			""")
	long countActiveSaved(@Param("userId") UUID userId);
}