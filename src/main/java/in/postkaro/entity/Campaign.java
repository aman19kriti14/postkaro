package in.postkaro.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "campaigns")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Campaign {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Column(nullable = false, length = 120)
	private String name;

	@Column(columnDefinition = "TEXT")
	private String brief; // what are you promoting, and why now

	@Column(length = 300)
	private String offer; // optional CTA

	@Column(length = 30)
	private String goal; // awareness, sales, launch, followers

	@Column(length = 20)
	private String cadence; // light, steady, heavy

	@Column(length = 30)
	private String tone;

	@Column(length = 30)
	private String visuals; // ai_all, ai_images, my_photos, text_only

	@Column(length = 30)
	private String look; // photographic, warm_grainy, editorial, illustrated

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "campaign_channels", joinColumns = @JoinColumn(name = "campaign_id"))
	@Column(name = "channel")
	@Builder.Default
	private Set<String> channels = new HashSet<>();

	@Column(nullable = false)
	private LocalDate startsOn;

	@Column(nullable = false)
	private LocalDate endsOn;

	@CreationTimestamp
	private Instant createdAt;

	@UpdateTimestamp
	private Instant updatedAt;
}