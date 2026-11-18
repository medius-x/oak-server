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
 * One resolved rung of the IGDB match ladder, keyed by the normalized search key:
 * {@code steam:<appid>} for the exact Steam-appid route, {@code name:<normalized title>}
 * for the fuzzy-search route. A non-null {@code igdbGameIgdbId} is a permanent HIT
 * (the game card lives in {@link IgdbGame}); a NULL one is a recorded MISS — replayed
 * as "not found" without touching IGDB until {@link #resolvedAt} outlives the miss TTL
 * (the game may have been added to IGDB since; hits never expire). User CONFIRM
 * choices pin entries the ladder could not resolve on its own.
 *
 * <p>A bare game id with NO FK on purpose (the {@code afk_channel_id} pattern): the
 * H2 test schema regenerates from entities and both sides are written in one service
 * call anyway. No {@code @Data}/@ToString/@EqualsAndHashCode — JPA entity convention.
 */
@Entity
@Table(name = "igdb_match_cache")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IgdbMatchCache {

    @Id
    @Column(name = "match_key", length = 256)
    private String matchKey;

    /** The matched {@link IgdbGame#getIgdbId()}, or null for a recorded miss. */
    @Column(name = "igdb_game_igdb_id")
    private Long igdbGameIgdbId;

    @Column(name = "resolved_at", nullable = false)
    private Instant resolvedAt;

    public IgdbMatchCache(String matchKey, Long igdbGameIgdbId, Instant resolvedAt) {
        this.matchKey = matchKey;
        this.igdbGameIgdbId = igdbGameIgdbId;
        this.resolvedAt = resolvedAt;
    }
}
