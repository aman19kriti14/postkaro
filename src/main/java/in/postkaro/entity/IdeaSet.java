package in.postkaro.entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "idea_sets", indexes = {
		@Index(name = "idx_idea_set_user_saved", columnList = "user_id, saved, updated_at") })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IdeaSet {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	// "This week · rain" — AI suggests one, user can rename on save
	@Column(nullable = false, length = 80)
	@Builder.Default
	private String name = "Untitled ideas";

	@Column(columnDefinition = "TEXT", nullable = false)
	private String brief;

	// brand_voice, product_list, past_top_posts, uploaded_photos
	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "idea_set_sources", joinColumns = @JoinColumn(name = "idea_set_id"))
	@Column(name = "source", length = 30)
	@Builder.Default
	private Set<String> sources = new LinkedHashSet<>();

	// reel, carousel, post, story
	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "idea_set_formats", joinColumns = @JoinColumn(name = "idea_set_id"))
	@Column(name = "format", length = 20)
	@Builder.Default
	private Set<String> formats = new LinkedHashSet<>();

	// instagram, facebook, linkedin, youtube, x
	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "idea_set_channels", joinColumns = @JoinColumn(name = "idea_set_id"))
	@Column(name = "channel", length = 30)
	@Builder.Default
	private Set<String> channels = new LinkedHashSet<>();

	// false = scratch generation; true = pinned under "Saved idea sets"
	@Column(nullable = false)
	@Builder.Default
	private boolean saved = false;

	@OneToMany(mappedBy = "ideaSet", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("position ASC")
	@Builder.Default
	private List<Idea> ideas = new ArrayList<>();

	@CreationTimestamp
	private Instant createdAt;

	@UpdateTimestamp
	private Instant updatedAt;
}