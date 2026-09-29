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
 * One custom sticker of the server-scoped catalog (phase 2). Unlike emojis (plain
 * {@code :name:} text), a sticker is referenced by id at SEND time and the MESSAGE carries a
 * denormalized copy ({@code sticker_name} + {@code sticker_file_key}) — so deleting this row
 * degrades old messages to a client-side tombstone instead of breaking rendering (the same
 * accepted tradeoff as the emoji catalog). Names are unique per PACK, not globally.
 *
 * <p>Object bytes live in MinIO under {@code stickers/<uuid>/<file>}; that prefix is
 * invisible to the attachment orphan sweep, and the night sweep has its own stickers pass
 * that reclaims abandoned upload slots (a minted key whose row was never created).
 */
@Entity
@Table(name = "stickers",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"pack_id", "name"}),
                @UniqueConstraint(columnNames = "file_key")
        })
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Sticker {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Sticker name, validated to {@code [a-z0-9_-]{1,32}} — the same ASCII charset as
     *  emoji names, so the upload form shares its sanitizer. Unique within the pack. */
    @Column(length = 32, nullable = false)
    private String name;

    @Column(name = "file_key", length = 512, nullable = false)
    private String fileKey;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pack_id", nullable = false)
    private StickerPack pack;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "uploaded_by", nullable = false)
    private User uploadedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Sticker(String name, String fileKey, StickerPack pack, User uploadedBy) {
        this.name = name;
        this.fileKey = fileKey;
        this.pack = pack;
        this.uploadedBy = uploadedBy;
    }
}
