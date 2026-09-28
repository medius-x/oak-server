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
 * One custom emoji of the server-scoped flat catalog (phase 1; packs land with stickers in
 * phase 2). {@code name} is the {@code :name:} token used in message TEXT — messages carry
 * no reference to this row, so deleting an emoji degrades old messages to plaintext
 * (accepted tradeoff, the rename-model precedent).
 *
 * <p>Object bytes live in MinIO under {@code custom-emojis/<uuid>/<file>}; that prefix is
 * INVISIBLE to the attachment orphan sweep (which lists only {@code attachments/}), and the
 * night sweep has its own custom-emojis pass that reclaims abandoned upload slots (a minted
 * key whose row was never created).
 */
@Entity
@Table(name = "custom_emojis",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = "name"),
                @UniqueConstraint(columnNames = "file_key")
        })
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustomEmoji {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The {@code :name:} token, validated to {@code [a-z0-9_-]{1,32}} (ASCII on purpose:
     *  the CLIENT-side parser uses the same charset, keeping token matching trivial). */
    @Column(length = 32, nullable = false)
    private String name;

    @Column(name = "file_key", length = 512, nullable = false)
    private String fileKey;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "uploaded_by", nullable = false)
    private User uploadedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public CustomEmoji(String name, String fileKey, User uploadedBy) {
        this.name = name;
        this.fileKey = fileKey;
        this.uploadedBy = uploadedBy;
    }
}
