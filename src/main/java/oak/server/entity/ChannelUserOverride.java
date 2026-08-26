package oak.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.NamedAttributeNode;
import jakarta.persistence.NamedEntityGraph;
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
 * A per-channel permission override for a single USER (Discord's "member override") — the
 * user-level twin of {@link ChannelRoleOverride}:
 * {@code allow} forces a permission on in this channel, {@code deny} forces it off, both as
 * {@link Permission} bitmasks. Resolution (see {@code AuthorizationService}): the user's own row
 * is applied LAST, on top of the role layers — a member allow beats an @everyone/role deny, which
 * is exactly what private channels need ("deny VIEW for @everyone, allow it for these two people").
 * {@code (channel_id, user_id)} is unique.
 */
@Entity
@Table(name = "channel_user_overrides",
        uniqueConstraints = @UniqueConstraint(name = "uk_channel_user_override", columnNames = {"channel_id", "user_id"}))
@NamedEntityGraph(name = "ChannelUserOverride.withUser",
        attributeNodes = @NamedAttributeNode("user"))
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChannelUserOverride {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "channel_id", nullable = false)
    private Channel channel;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

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

    public ChannelUserOverride(Channel channel, User user, long allow, long deny) {
        this.channel = channel;
        this.user = user;
        this.allow = allow;
        this.deny = deny;
    }
}
