package oak.server.repository;

import oak.server.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * JPA repository for {@link RefreshToken}. Lookups are by the stored hash, never by the raw token.
 */
@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** All tokens in a family — used to revoke the whole chain on reuse of a revoked token. */
    List<RefreshToken> findByFamilyId(String familyId);

    /**
     * Deletes every token whose {@code expiresAt} is before {@code cutoff}. Purging by expiry
     * (not by {@code revoked}) is deliberate: a revoked-but-not-yet-expired token must stay around
     * so reuse-detection can still find it by hash and revoke its family. The table is still
     * bounded — nothing outlives the refresh lifetime. Requires an active transaction.
     */
    long deleteByExpiresAtBefore(Instant cutoff);
}
