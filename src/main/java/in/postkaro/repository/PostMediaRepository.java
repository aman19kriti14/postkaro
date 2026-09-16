package in.postkaro.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import in.postkaro.entity.PostMedia;

public interface PostMediaRepository extends JpaRepository<PostMedia, UUID> {
}