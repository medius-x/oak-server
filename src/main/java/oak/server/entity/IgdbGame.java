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

    /** The icon-bytes cache cap (1.1 MB, user decision 2026-11-23: a game whose
     *  ONLY icon is a fat ~1 MB source must not lose it); icons are usually
     *  30–300 KB, anything bigger is refused rather than stored (a hostile
     *  upload cannot bloat the row). */
    public static final int SGDB_ICON_MAX_BYTES = 1100 * 1024;

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

    /** The picked SteamGridDB icon's CDN URL (V23): provenance for the cached
     *  bytes below (re-fetches, debugging). Null = not resolved, or SGDB is
     *  off / has nothing for the game. */
    @Column(name = "sgdb_icon_url", columnDefinition = "text")
    private String sgdbIconUrl;

    /** The icon's cached BYTES (V23): clients get them from
     *  {@code GET /api/games/{id}/icon} — the CDN itself is unreachable from
     *  some client machines (DPI/proxy), so the server is the only door. Icons
     *  are 30–150 KB; a {@link #SGDB_ICON_MAX_BYTES} guard caps the row. */
    @Column(name = "sgdb_icon_bytes", columnDefinition = "bytea")
    private byte[] sgdbIconBytes;

    /** The bytes' content type — the CDN RESPONSE header verbatim
     *  ({@code image/png} / {@code image/x-icon}; the API metadata's
     *  {@code image/vnd.microsoft.icon} is not what the CDN sends). */
    @Column(name = "sgdb_icon_mime", length = 64)
    private String sgdbIconMime;

    /** The IGDB cover THUMBNAIL bytes (V23, the square {@code t_thumb} preset):
     *  the panel's fallback picture — fetched ONCE from images.igdb.com and
     *  served by {@code GET /api/games/{id}/cover}, because that CDN is
     *  unreachable from some client machines exactly like SGDB's. */
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
