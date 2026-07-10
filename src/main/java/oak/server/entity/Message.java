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
 * an attachment, or both. Immutable for now (no edit/delete).
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
