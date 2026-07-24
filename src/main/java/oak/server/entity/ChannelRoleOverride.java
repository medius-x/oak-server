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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * A per-channel permission override for a single role (Discord-style): {@code allow} forces a
 * permission on in this channel, {@code deny} forces it off, both as {@link Permission} bitmasks.
 * Resolution (see {@code AuthorizationService}): for a (user, channel, permission), gather the
 * overrides of every role the user has (including {@code @everyone}); a deny bit wins, then an allow
 * bit, otherwise the role's base permission applies. {@code (channel_id, role_id)} is unique.
 */
@Entity
@Table(name = "channel_role_overrides",
        uniqueConstraints = @UniqueConstraint(name = "uk_channel_role_override", columnNames = {"channel_id", "role_id"}))
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChannelRoleOverride {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "channel_id", nullable = false)
    private Channel channel;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    @Column(nullable = false)
    private long allow;

    @Column(nullable = false)
    private long deny;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    public ChannelRoleOverride(Channel channel, Role role, long allow, long deny) {
        this.channel = channel;
        this.role = role;
        this.allow = allow;
        this.deny = deny;
    }
}
