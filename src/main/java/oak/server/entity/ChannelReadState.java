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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * Per-(user, channel) read pointer: the highest message id the user has read in the channel. Drives
 * unread/mention counts ({@code count messages where id > last_read}) — the Discord model. Null
 * {@code lastReadMessageId} means "nothing read yet" (everything unread). Seeded on registration so
 * a new member does not see the entire pre-existing archive as unread (see {@code AuthService}).
 *
 * <p>No {@code @Data}/@ToString/@EqualsAndHashCode (lazy/unstable hashing, like the other entities).
 */
@Entity
@Table(name = "channel_read_states",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "channel_id"}))
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChannelReadState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "channel_id", nullable = false)
    private Channel channel;

    /** Highest message id read in the channel (null = nothing read yet). */
    @Column(name = "last_read_message_id")
    private Long lastReadMessageId;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    public ChannelReadState(User user, Channel channel, Long lastReadMessageId) {
        this.user = user;
        this.channel = channel;
        this.lastReadMessageId = lastReadMessageId;
    }
}
