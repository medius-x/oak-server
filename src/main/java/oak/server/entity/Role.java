package oak.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * A role on the instance. Roles are global (single-tenant: the whole OAK instance is one "server"),
 * assigned to users many-to-many via {@code user_roles}. {@link #permissions} is a {@link Permission}
 * bitmask; {@link #position} drives hierarchy (higher = more authority — a member can only act on
 * those whose top role is strictly lower); {@link #kind} marks the protected built-ins.
 *
 * <p>No {@code @Data}/@ToString/@EqualsAndHashCode (lazy/unstable hashing, like the other entities).
 */
@Entity
@Table(name = "roles")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    /** ARGB color int shown as the role's chip, or null for the default (no color). */
    @Column
    private Integer color;

    /** Hierarchy rank — higher beats lower. {@code @everyone} = 0, {@code OWNER} = a large constant. */
    @Column(nullable = false)
    private int position;

    /** Granted permissions as a {@link Permission} bitmask. */
    @Column(name = "permissions", nullable = false)
    private long permissions;

    @Column(name = "mentionable", nullable = false)
    private boolean mentionable;

    /** Whether members with this role are grouped separately in the member list. */
    @Column(name = "hoist", nullable = false)
    private boolean hoist;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private RoleKind kind;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    public Role(String name, int position, long permissions, boolean mentionable, boolean hoist, RoleKind kind) {
        this.name = name;
        this.position = position;
        this.permissions = permissions;
        this.mentionable = mentionable;
        this.hoist = hoist;
        this.kind = kind;
    }
}
