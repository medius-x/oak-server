package oak.server.repository;

import oak.server.entity.IgdbGame;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** Cached IGDB game cards; the id IS the IGDB game id (see {@link IgdbGame}).
 *  MANUAL activities' cards live here too under NEGATIVE ids (the phase-3
 *  round: a hand-written "playing CS2" sessions like a detected game, just
 *  without IGDB metadata). */
public interface IgdbGameRepository extends JpaRepository<IgdbGame, Long> {

    /** The manual card for an activity name (case-insensitive), if one exists. */
    Optional<IgdbGame> findFirstByIgdbIdLessThanAndNameIgnoreCase(long manualBoundary, String name);

    /** The lowest manual id (absent when no manual card exists yet). */
    @Query("SELECT MIN(g.igdbId) FROM IgdbGame g WHERE g.igdbId < 0")
    Optional<Long> lowestManualId();

    /** Cards whose SGDB icon bytes have not landed yet — the hourly retry
     *  sweep's input (the miss marker filters the answered ones out). */
    java.util.List<IgdbGame> findBySgdbIconBytesIsNull();

    /** The FIRST-WINS icon store (2026-11-24): exactly one delivery — the
     *  server lane's own attempt OR the first client's offer — may fill the
     *  bytes; the WHERE clause makes the race atomic and every loser a
     *  harmless no-op. Also clears the pending residue (partial bytes, miss
     *  marker). Returns 1 when THIS call stored the icon, 0 when one was
     *  already there. {@code @Transactional} is LOAD-BEARING (the 2026-11-24
     *  production crash): a {@code @Modifying} query demands a transaction,
     *  and the icon lane's background thread has none — without this
     *  annotation every store from the lane threw
     *  TransactionRequiredException, silently (the executor swallows
     *  uncaught throwables). */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE IgdbGame g SET g.sgdbIconUrl = :url, g.sgdbIconMime = :mime, g.sgdbIconBytes = :bytes, "
            + "g.sgdbIconMissing = false, g.sgdbIconPartial = NULL "
            + "WHERE g.igdbId = :igdbId AND g.sgdbIconBytes IS NULL")
    int storeIconIfAbsent(@Param("igdbId") long igdbId, @Param("url") String url,
                          @Param("mime") String mime, @Param("bytes") byte[] bytes);

    /** Persists a burst attempt's partial bytes — Range-resume material for
     *  the next attempt; a no-op once ANY source delivered the icon. Same
     *  load-bearing {@code @Transactional} as {@link #storeIconIfAbsent}. */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE IgdbGame g SET g.sgdbIconUrl = :url, g.sgdbIconPartial = :partial "
            + "WHERE g.igdbId = :igdbId AND g.sgdbIconBytes IS NULL")
    int storePartialIfUndelivered(@Param("igdbId") long igdbId, @Param("url") String url,
                                  @Param("partial") byte[] partial);
}
