package oak.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * One invite link: a single-use registration secret minted by a {@code MANAGE_MEMBERS}
 * holder. The secret is random (24 bytes, base64url) and travels only inside the link the
 * server assembles ({@code http://host:port/#invite=<secret>}); registration CLAIMS it
 * with an atomic guarded DELETE ({@code InviteRepository.claim}) — a used link simply
 * stops existing, so exactly one user can register per secret (a repeat falls into
 * "unknown secret"). TTL: {@code expiresAt} is stamped at mint time from the profile's
 * current setting and, by user decision, retroactively rescheduled
 * ({@code createdAt + newTtl}) whenever the setting changes. Revoked/expired rows are
 * swept by {@code InvitesCleanup}.
 *
 * <p>No {@code @Data}/@ToString/@EqualsAndHashCode — JPA entity convention (matches {@link User}).
 */
@Entity
@Table(name = "invites",
        uniqueConstraints = @UniqueConstraint(columnNames = "secret"))
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Invite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The raw single-use secret (base64url, 32 chars). Plaintext on purpose: the list UI
     *  must be able to re-render the copyable link, unlike refresh tokens (hashed). */
    @Column(nullable = false, length = 64)
    private String secret;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** When the link stops working; rescheduled retroactively on TTL changes. */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Manually invalidated ("Expire"); the invite no longer accepts registrations. */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean revoked;

    public Invite(String secret, User createdBy, Instant expiresAt) {
        this.secret = secret;
        this.createdBy = createdBy;
        this.expiresAt = expiresAt;
    }

    /** Whether the invite still accepts a registration (the list shows only these). The
     *  authority for the race is the atomic {@code claim} DELETE — this read is the
     *  human-readable pre-check only. */
    public boolean isActive(Instant now) {
        return !revoked && expiresAt.isAfter(now);
    }
}
