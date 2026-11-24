package oak.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One cached IGDB game card: the projection of an IGDB {@code /v4/games} row the
 * auto-detect ladder serves to clients (name, cover, genres, release year). Written
 * once on first sight and never expired — a game's card is stable, and re-querying
 * IGDB for it would burn the instance's 4-requests-per-second budget for nothing.
 *
 * <p>{@code genres} is a JSON array string ({@code [{"id":5,"name":"Shooter"}]}) — the
 * {@code messages.attachments} pattern: a text column parsed in code, so both the H2
 * test schema and PostgreSQL stay dumb storage. No {@code @Data}/@ToString/
 * @EqualsAndHashCode — JPA entity convention.
 */
@Entity
@Table(name = "igdb_games")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IgdbGame {


    /** The IGDB game id — this IS the natural key (IGDB ids are stable). An ASSIGNED
     *  id on purpose: no {@code @GeneratedValue}, or Hibernate would silently generate
     *  its own sequence and ignore the external id we hand it. */
    @Id
    @Column(name = "igdb_id")
    private Long igdbId;

    @Column(nullable = false)
    private String name;

    /** IGDB {@code cover.image_id}; the client assembles the CDN URL with its own size
     *  preset ({@code https://images.igdb.com/igdb/image/upload/t_cover_big/<id>.jpg}).
     *  Null = no cover on IGDB. */
    @Column(name = "cover_image_id", length = 64)
    private String coverImageId;

    @Column(nullable = false, columnDefinition = "text")
    private String genres;

    /** First release date as unix seconds, null when IGDB does not know it. */
    @Column(name = "first_release_date")
    private Long firstReleaseDate;

    @Column(name = "cached_at", nullable = false)
    private Instant cachedAt;






    /** The IGDB cover THUMBNAIL bytes (V23, the square {@code t_thumb} preset):
     *  the panel's fallback picture — fetched ONCE from images.igdb.com and
     *  served by {@code GET /api/games/{id}/cover}, because that CDN is
     *  unreachable from some client machines. */
    @Column(name = "cover_thumb_bytes", columnDefinition = "bytea")
    private byte[] coverThumbBytes;

    @Column(name = "cover_thumb_mime", length = 64)
    private String coverThumbMime;

    public IgdbGame(Long igdbId, String name, String coverImageId, String genres,
                    Long firstReleaseDate, Instant cachedAt) {
        this.igdbId = igdbId;
        this.name = name;
        this.coverImageId = coverImageId;
        this.genres = genres;
        this.firstReleaseDate = firstReleaseDate;
        this.cachedAt = cachedAt;
    }
}
