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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * An opaque, server-stored refresh token. Only its SHA-256 hash is persisted (never the raw
 * token). Revocable and rotated on each use: a login or refresh issues a new one, and a refresh
 * revokes the presented one — so a leaked refresh is usable at most once. The raw token is handed
 * to the client exactly once.
 *
 * <p>Tokens are grouped into a <b>family</b> ({@code familyId}): a login starts a new family and
 * every refresh of it continues the same family. Re-presenting an <i>already revoked</i> token is
 * a theft signal — the whole family is then revoked so the attacker's stolen copy and every
 * downstream token die together (reuse-detection). {@code familyId} is mapped nullable only so
 * {@code ddl-auto=update} can add the column to a dev DB with leftover rows; the code always sets
 * it for new tokens, and a null family (legacy row) simply skips family-wide revocation.
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token_hash", nullable = false, unique = true, length = 512)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean revoked;

    /** Groups a login's refresh lineage so reuse of a revoked token revokes the whole chain. */
    @Column(name = "family_id", length = 36)
    private String familyId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public RefreshToken(User user, String tokenHash, Instant expiresAt, String familyId) {
        this.user = user;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.revoked = false;
        this.familyId = familyId;
    }
}
