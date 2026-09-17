package in.postkaro.entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One row per user: brand look + voice. Read by AI studio and caption
 * generation.
 */
@Entity
@Table(name = "brand_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BrandSettings {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@OneToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id", nullable = false, unique = true)
	private User user;

	// ---------- brand ----------

	@Column(length = 1000)
	private String logoUrl;

	// hex values, in the order the user set them (max 5)
	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "brand_colors", joinColumns = @JoinColumn(name = "brand_settings_id"))
	@OrderColumn(name = "position")
	@Column(name = "hex", length = 7)
	@Builder.Default
	private List<String> colors = new ArrayList<>();

	@Column(length = 60)
	private String headingFont; // "Cormorant Garamond"

	@Column(length = 60)
	private String bodyFont; // "Lora"

	// ---------- voice ----------

	@Column(length = 20)
	@Builder.Default
	private String tone = "warm"; // warm, playful, informative, festive, plain

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "brand_languages", joinColumns = @JoinColumn(name = "brand_settings_id"))
	@Column(name = "language", length = 20)
	@Builder.Default
	private Set<String> languages = new LinkedHashSet<>(); // english, hindi, hinglish

	@Column(columnDefinition = "TEXT")
	private String voiceDescription; // "How you sound"

	@Column(length = 500)
	private String wordsToUse; // comma-separated, as typed

	@Column(length = 500)
	private String wordsToAvoid;

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "brand_sample_posts", joinColumns = @JoinColumn(name = "brand_settings_id"))
	@OrderColumn(name = "position")
	@Column(name = "text", length = 2200)
	@Builder.Default
	private List<String> samplePosts = new ArrayList<>(); // max 5

	@CreationTimestamp
	private Instant createdAt;

	@UpdateTimestamp
	private Instant updatedAt;
}