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
 * Membership row tying a user to a DM / group-DM {@link Channel}. A 1:1 DM has exactly two rows; a
 * group DM has one row per participant. Presence of a row is the single source of truth for DM access
 * (see the DM branch in {@code AuthorizationService.hasInChannel}): a participant gets all channel
 * capabilities, a non-participant gets none — even the instance OWNER.
 *
 * <p>{@code (channel_id, user_id)} is unique: a user is a participant of a channel at most once.
 */
@Entity
@Table(name = "channel_participants",
        uniqueConstraints = @UniqueConstraint(columnNames = {"channel_id", "user_id"}))
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChannelParticipant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "channel_id", nullable = false)
    private Channel channel;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @CreationTimestamp
    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    public ChannelParticipant(Channel channel, User user) {
        this.channel = channel;
        this.user = user;
    }
}
