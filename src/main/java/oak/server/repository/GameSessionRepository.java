package oak.server.repository;

import oak.server.entity.GameSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** Play sessions (the achievements foundation); rows are opened/closed by the
 *  roster activity transitions and the stale-session sweeper. */
public interface GameSessionRepository extends JpaRepository<GameSession, Long> {

    /** The user's currently open sessions (normally at most one). */
    List<GameSession> findByUsernameAndEndedAtIsNull(String username);

    /** Every open session (the sweeper's scan — closed by missing presence). */
    List<GameSession> findByEndedAtIsNull();

    /** Everything a user ever played (the "my games" aggregation). */
    List<GameSession> findByUsername(String username);

    /** The manual-hours merge (2026-11-25): moves ONE USER's sessions from a
     *  virtual manual card onto the real game — a bulk statement, so it carries
     *  the {@code clearAutomatically}/{@code flushAutomatically} discipline. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update GameSession s set s.igdbGameIgdbId = :toIgdbId"
            + " where s.username = :username and s.igdbGameIgdbId = :fromIgdbId")
    int moveSessions(@Param("username") String username,
                     @Param("fromIgdbId") Long fromIgdbId,
                     @Param("toIgdbId") Long toIgdbId);

    /** Whether ANY user still sessions against the card (the merge's cleanup test). */
    boolean existsByIgdbGameIgdbId(Long igdbGameIgdbId);
}
