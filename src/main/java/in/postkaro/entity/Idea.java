package in.postkaro.entity;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "ideas", indexes = { @Index(name = "idx_idea_set_position", columnList = "idea_set_id, position") })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Idea {

	public enum Status {
		ACTIVE, // shown as a card
		DISMISSED, // hidden, can be restored
		DRAFTED,
		FAILED// turned into a post (still shown, linked to the draft)
	}

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "idea_set_id", nullable = false)
	private IdeaSet ideaSet;

	// order the AI returned them in
	@Column(nullable = false)
	private int position;

	@Column(nullable = false, length = 20)
	private String format; // reel, carousel, post, story

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "idea_channels", joinColumns = @JoinColumn(name = "idea_id"))
	@Column(name = "channel", length = 30)
	@Builder.Default
	private Set<String> channels = new LinkedHashSet<>();

	@Column(nullable = false, length = 120)
	private String title; // "Brew it wrong, then brew it right"

	@Column(nullable = false, length = 400)
	private String description; // the 1–2 line pitch

	// Starting caption the draft is created with
	@Column(columnDefinition = "TEXT")
	private String caption;

	// Red line on the card. Only ever built from real post_metrics numbers; null
	// when no data
	@Column(length = 200)
	private String insight;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	@Builder.Default
	private Status status = Status.ACTIVE;

	// set once "Draft" / "Post now" / "Schedule" creates a post
	@Column(name = "draft_post_id")
	private UUID draftPostId;

	// set by "Make visual" (fal.ai)
	@Column(length = 1000)
	private String visualUrl;

	@CreationTimestamp
	private Instant createdAt;

	@UpdateTimestamp
	private Instant updatedAt;
}