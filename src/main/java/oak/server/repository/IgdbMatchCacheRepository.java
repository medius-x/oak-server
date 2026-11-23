package oak.server.repository;

import oak.server.entity.IgdbMatchCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** Resolved rungs of the IGDB match ladder, keyed by the normalized search key. */
public interface IgdbMatchCacheRepository extends JpaRepository<IgdbMatchCache, String> {

    /** The {@code steam:<appid>} keys that resolved to a game — the icon layer's
     *  appid source (SGDB icons are queried by the Steam appid directly). */
    @Query("select c.matchKey from IgdbMatchCache c where c.igdbGameIgdbId = :igdbId and c.matchKey like 'steam:%'")
    List<String> findSteamKeys(@Param("igdbId") long igdbId);
}
