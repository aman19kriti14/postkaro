package in.postkaro.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import in.postkaro.enums.CampaignStatus;
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
	@Builder.Default
	private String name = "Untitled campaign";

	@Column(columnDefinition = "TEXT")
	private String brief;

	@Column(length = 300)
	private String offer;

	@Column(length = 30)
	private String goal;

	@Column(length = 20)
	private String cadence;

	@Column(length = 30)
	private String tone;

	@Column(length = 30)
	private String visuals;

	@Column(length = 30)
	private String look;

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "campaign_channels", joinColumns = @JoinColumn(name = "campaign_id"))
	@Column(name = "channel")
	@Builder.Default
	private Set<String> channels = new HashSet<>();

	private LocalDate startsOn; // nullable while drafting

	private LocalDate endsOn; // nullable while drafting

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	@Builder.Default
	private CampaignStatus status = CampaignStatus.DRAFT;

	@Column(nullable = false)
	@Builder.Default
	private int currentStep = 1; // 1 Create, 2 Review, 3 Schedule, 4 Publish

	@Column(nullable = false)
	@Builder.Default
	private boolean autoPublish = true;

	@CreationTimestamp
	private Instant createdAt;

	@UpdateTimestamp
	private Instant updatedAt;
}