package oak.server.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import oak.server.repository.RefreshTokenRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Periodically purges expired refresh tokens so the {@code refresh_tokens} table stays bounded.
 * Purging is by {@code expiresAt} (not by {@code revoked}): a revoked-but-not-yet-expired token
 * must remain so reuse-detection can still find it by hash and revoke its family. Nothing in the
 * table outlives the refresh lifetime, so growth is capped regardless.
 *
 * <p>The purge logic lives in {@link #purgeExpired()} (callable directly from tests) separate from
 * the {@link Scheduled} trigger, so tests don't depend on scheduler timing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefreshTokenCleanup {

    private final RefreshTokenRepository refreshTokenRepository;

    /** Deletes every refresh token whose expiry is in the past. Returns how many were removed. */
    @Transactional
    public long purgeExpired() {
        long deleted = refreshTokenRepository.deleteByExpiresAtBefore(Instant.now());
        if (deleted > 0) {
            log.info("Purged {} expired refresh token(s)", deleted);
        }
        return deleted;
    }

    @Scheduled(fixedDelayString = "${oak.security.refresh-cleanup-interval:PT6H}")
    @Transactional   // the scheduler enters HERE through the proxy, but purgeExpired() is then a
    // SELF-invocation that bypasses it — the transaction must be opened at this entry point.
    // Without it every purge died with TransactionRequiredException (a live bug since birth:
    // the cleanup never actually deleted anything; the existing test called purgeExpired()
    // externally, through the proxy, and so never saw it).
    void scheduledPurge() {
        purgeExpired();
    }
}
