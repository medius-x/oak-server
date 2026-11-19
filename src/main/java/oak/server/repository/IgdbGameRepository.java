package oak.server.repository;

import oak.server.entity.IgdbGame;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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
}
