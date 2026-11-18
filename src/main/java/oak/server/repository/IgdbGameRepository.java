package oak.server.repository;

import oak.server.entity.IgdbGame;
import org.springframework.data.jpa.repository.JpaRepository;

/** Cached IGDB game cards; the id IS the IGDB game id (see {@link IgdbGame}). */
public interface IgdbGameRepository extends JpaRepository<IgdbGame, Long> {
}
