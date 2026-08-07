package oak.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * A channel in the instance. The whole OAK instance is one server (no guilds), so a
 * channel is a top-level container — either {@link ChannelType#TEXT} or {@link ChannelType#VOICE}.
 */
@Entity
@Table(name = "channels")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Channel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ChannelType type;

    /** The owner of a group DM (the creator, who may add/remove participants); null for {@code TEXT},
     *  {@code VOICE}, and 1:1 {@code DM}. Nullable so the {@code ddl-auto=update} addition needs no
     *  default on the already-populated {@code channels} table. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private User owner;

    /** Deduplication key for a 1:1 DM: {@code min(aId) + "_" + max(bId)}. Unique + nullable (Postgres
     *  treats NULLs as distinct), so only 1:1 DMs carry it — group DMs and public channels stay null.
     *  Two concurrent "create a 1:1 DM with the same user" collapse to a unique-violation, which the
     *  service catches and re-resolves to the existing channel. */
    @Column(name = "dm_key", length = 41, unique = true)
    private String dmKey;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    public Channel(String name, ChannelType type) {
        this.name = name;
        this.type = type;
    }
}
