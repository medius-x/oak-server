package oak.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * One sticker pack of the server-scoped catalog (phase 2). A pack is pure grouping for the
 * picker tray and the settings screen — messages never reference it. Names are free text,
 * unique across the instance.
 */
@Entity
@Table(name = "sticker_packs",
        uniqueConstraints = @UniqueConstraint(columnNames = "name"))
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StickerPack {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Display name (free text, trimmed server-side, unique). */
    @Column(length = 32, nullable = false)
    private String name;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public StickerPack(String name) {
        this.name = name;
    }
}
