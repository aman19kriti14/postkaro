package in.postkaro.repository;

import in.postkaro.entity.Post;
import in.postkaro.enums.PostStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PostRepository extends JpaRepository<Post, UUID> {

	@Query("SELECT p FROM Post p LEFT JOIN FETCH p.media LEFT JOIN FETCH p.channels WHERE p.id = :id AND p.user.id = :userId")
	Optional<Post> findByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

	List<Post> findByUserIdAndStatusOrderByCreatedAtDesc(UUID userId, PostStatus status);

	List<Post> findByUserIdOrderByCreatedAtDesc(UUID userId);
}