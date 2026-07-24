package oak.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * Application user. Persists to the {@code users} table in PostgreSQL.
 * The stored credential is a hash (e.g. BCrypt), never a plaintext password.
 *
 * <p>Note: no {@code @Data}/@ToString/@EqualsAndHashCode here — those would pull
 * in lazy-loading and unstable id-based hashing on a JPA entity.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    /** MinIO object key of the user's avatar, or null when none is set (presigned GET is signed per request). */
    @Column(name = "avatar_key", length = 512)
    private String avatarKey;

    /** MinIO object key of the original (pre-crop) image used for the avatar — lets the user re-crop
     *  without re-selecting a file. Loaded lazily via {@code GET /api/users/me/avatar/original}. */
    @Column(name = "avatar_original_key", length = 512)
    private String avatarOriginalKey;

    /** Roles assigned to this member (the {@code @everyone} role is implicit and never listed here).
     *  Cascade PERSIST/MERGE so modifying the collection and saving the user writes the join rows;
     *  the {@code @everyone} role is folded into resolution separately. */
    @ManyToMany(fetch = FetchType.LAZY, cascade = {jakarta.persistence.CascadeType.PERSIST, jakarta.persistence.CascadeType.MERGE})
    @JoinTable(
            name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new HashSet<>();

    /** Banned members cannot log in or open a STOMP connection (checked in AuthService/AuthChannelInterceptor).
     *  The {@code columnDefinition} default is required for {@code ddl-auto=update}: adding a NOT NULL column
     *  to an already-populated {@code users} table without a default is rejected by PostgreSQL
     *  ("column contains null values"). With the default, existing rows back-fill to {@code false}. */
    @Column(name = "banned", nullable = false, columnDefinition = "boolean default false")
    private boolean banned;

    public User(String username, String passwordHash) {
        this.username = username;
        this.passwordHash = passwordHash;
    }
}
