package in.postkaro.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import in.postkaro.entity.IdeaSet;

public interface IdeaSetRepository extends JpaRepository<IdeaSet, UUID> {

	// Full set with its ideas, for the studio page
	@Query("""
			select distinct s from IdeaSet s
			left join fetch s.ideas
			where s.id = :id and s.user.id = :userId
			""")
	Optional<IdeaSet> findFull(@Param("id") UUID id, @Param("userId") UUID userId);

	Optional<IdeaSet> findByIdAndUserId(UUID id, UUID userId);

	// Sidebar "Saved idea sets": [id, name, ideaCount, updatedAt]
	@Query("""
			select s.id, s.name,
			       (select count(i) from Idea i
			        where i.ideaSet = s and i.status <> in.postkaro.entity.Idea.Status.DISMISSED),
			       s.updatedAt
			from IdeaSet s
			where s.user.id = :userId and s.saved = true
			order by s.updatedAt desc
			""")
	List<Object[]> savedSummaries(@Param("userId") UUID userId, Pageable page);

	// Most recent set (saved or not), so the studio reopens where the user left off
	@Query("""
			select s.id from IdeaSet s
			where s.user.id = :userId
			order by s.createdAt desc
			""")
	List<UUID> latestIds(@Param("userId") UUID userId, Pageable page);
}