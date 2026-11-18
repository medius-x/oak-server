package oak.server.repository;

import oak.server.entity.IgdbMatchCache;
import org.springframework.data.jpa.repository.JpaRepository;

/** Resolved rungs of the IGDB match ladder, keyed by the normalized search key. */
public interface IgdbMatchCacheRepository extends JpaRepository<IgdbMatchCache, String> {
}
