package oak.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * The 1:1 companion row of a {@link ChannelType#DISCUSSION} channel: the FROZEN snapshot of the
 * message the discussion was started on (the {@code ForwardInfo} discipline) plus the bare-id
 * links. The snapshot is deliberate: the discussion's tile (sidebar, origin panel) keeps
 * rendering author/snippet/time even after the origin message is deleted or reaped by the
 * storage GC — only a dangling id would remain otherwise.
 *
 * <p>Bare ids throughout (the {@code Message.repliedToId} discipline — no FK, no cascade; the
 * manual-cleanup discipline owns orphaning: {@code DiscussionService.deleteInternals} runs from
 * both the close endpoint and the parent channel's deletion).
 *
 * <p>{@code originMessageId} is UNIQUE: one discussion per message. {@code channelId} is the
 * DISCUSSION channel's own id (1:1 with the {@code Channel} row).
 */
@Entity
@Table(name = "discussions")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Discussion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The DISCUSSION channel carrying the thread's messages (1:1 with this row). */
    @Column(name = "channel_id", nullable = false, unique = true)
    private Long channelId;

    /** The parent (always TEXT) channel the discussion lives under; permissions resolve through it. */
    @Column(name = "parent_channel_id", nullable = false)
    private Long parentChannelId;

    /** The message the discussion was started on (unique — one thread per message). */
    @Column(name = "origin_message_id", nullable = false, unique = true)
    private Long originMessageId;

    /** The origin's text at start time, flattened and capped by the service. */
    @Column(name = "origin_snippet", length = 200)
    private String originSnippet;

    private Long originAuthorId;

    /** The origin author's username at start time (denormalized — the wire shows a name). */
    @Column(name = "origin_author_name", length = 50)
    private String originAuthorName;

    private Instant originCreatedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** The thread's latest message moment — maintained by {@code DiscussionService} on every
     *  post; the "mine" list is ordered by it (most recently active first). */
    @Column(name = "last_message_at")
    private Instant lastMessageAt;

    public Discussion(Long channelId, Long parentChannelId, Long originMessageId, String originSnippet,
                      Long originAuthorId, String originAuthorName, Instant originCreatedAt) {
        this.channelId = channelId;
        this.parentChannelId = parentChannelId;
        this.originMessageId = originMessageId;
        this.originSnippet = originSnippet;
        this.originAuthorId = originAuthorId;
        this.originAuthorName = originAuthorName;
        this.originCreatedAt = originCreatedAt;
    }
}
