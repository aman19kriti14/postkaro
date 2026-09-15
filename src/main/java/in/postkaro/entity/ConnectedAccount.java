package in.postkaro.entity;

import in.postkaro.enums.SocialPlatform;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "connected_accounts",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "platform", "platform_user_id"}))
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class ConnectedAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SocialPlatform platform;

    @Column(nullable = false)
    private String platformUserId;

    @Column(nullable = false)
    private String platformUsername;

    @Column(nullable = false)
    private String platformDisplayName;

    @Column(length = 500)
    private String avatarUrl;

    @Column(columnDefinition = "TEXT")
    private String accessToken;

    @Column(columnDefinition = "TEXT")
    private String refreshToken;

    private Instant accessTokenExpiresAt;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    @CreationTimestamp
    private Instant connectedAt;
}
