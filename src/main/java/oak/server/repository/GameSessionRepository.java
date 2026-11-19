package oak.server.repository;

import oak.server.entity.GameSession;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
