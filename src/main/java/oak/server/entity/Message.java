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
 * A chat message: belongs to a {@link Channel}, authored by a {@link User}. May carry an
 * attachment stored in MinIO (referenced by object key + display name) — a message has content,
 * an attachment, or both. Content is editable by the author ({@code editedAt} records the last
 * edit; null = never edited); attachments are not editable.
 */
@Entity
@Table(name = "messages")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "channel_id", nullable = false)
    private Channel channel;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false)
    private User author;

    @Column(length = 4000)
    private String content;   // nullable: an attachment-only message has no text

    @Column(name = "attachment_key", length = 512)
    private String attachmentKey;       // MinIO object key, null when there is no attachment

    @Column(name = "attachment_file_name", length = 255)
    private String attachmentFileName;  // original file name, for display

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** When the content was last edited; null = never edited. Set manually by
     *  {@code MessageService.edit} only — deliberately NOT {@code @UpdateTimestamp}, which would
     *  stamp every entity update (not just real edits) and expects a non-null value. */
    @Column(name = "edited_at")
    private Instant editedAt;

    /** The message this one replies to (same channel only — enforced on create), null = not a
     *  reply. Deliberately a bare id with NO FK/@ManyToOne: nothing in the project uses ON DELETE
     * CASCADE (manual cleanup discipline), and a self-referential FK would force a detach/cascade
     * dance every time a replied-to message is deleted. Instead a dangling id is detected at read
     * time (batch miss) and rendered as "original deleted". */
    @Column(name = "replied_to_id")
    private Long repliedToId;

    // ---- Forward origin snapshot (Discord-style forward card). A forward is a NEW message whose
    // content/attachment are a COPY plus these denormalized fields describing where the original
    // lived. A snapshot (not a live reference): edits/deletion of the original do not propagate.
    // The message's own author is the FORWARDER; the card shows forward_author_*.

    /** Display name of the instance the original came from (the server profile name, or the
     *  client-supplied name for a cross-instance snapshot — unverifiable, see ForwardInfo.trusted). */
    @Column(name = "forward_origin_server", length = 100)
    private String forwardOriginServer;

    /** The original's channel display name at forward time. */
    @Column(name = "forward_origin_channel", length = 100)
    private String forwardOriginChannel;

    /** The original author's id (null allowed for cross-instance snapshots). */
    @Column(name = "forward_author_id")
    private Long forwardAuthorId;

    /** The original author's username at forward time. */
    @Column(name = "forward_author_name", length = 50)
    private String forwardAuthorName;

    /** When the original was created (for the card's timestamp). */
    @Column(name = "forward_created_at")
    private Instant forwardCreatedAt;

    /** Anti-spoofing marker: TRUE only when THIS server derived the origin itself (intra-instance
     *  forward); false for a client-supplied cross-instance snapshot, which cannot be verified.
     *  Clients render "unverified" on untrusted cards. Never derived from the other columns — a
     *  snapshot could copy a truthful-looking originServer. */
    @Column(name = "forward_trusted")
    private Boolean forwardTrusted;

    public Message(Channel channel, User author, String content) {
        this.channel = channel;
        this.author = author;
        this.content = content;
    }

    public Message(Channel channel, User author, String content, String attachmentKey, String attachmentFileName) {
        this.channel = channel;
        this.author = author;
        this.content = content;
        this.attachmentKey = attachmentKey;
        this.attachmentFileName = attachmentFileName;
    }
}
