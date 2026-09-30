package oak.server.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import oak.server.repository.InviteRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Periodically purges dead invite rows so the {@code invites} table stays bounded. Spent
 * links are already gone the moment they are claimed (an atomic DELETE in the
 * registration path) — this sweep only reaps what a claim can never reach: REVOKED rows
 * ("Expire") and EXPIRED ones (TTL in the past). Neither can become active again, so
 * deletion loses nothing.
 *
 * <p>The purge logic lives in {@link #purgeInactive()} (callable directly from tests)
 * separate from the {@link Scheduled} trigger, so tests don't depend on scheduler timing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InvitesCleanup {

    private final InviteRepository inviteRepository;

    /** Deletes every revoked or expired invite; returns how many were removed. */
    @Transactional
    public long purgeInactive() {
        long deleted = inviteRepository.deleteInactive(Instant.now());
        if (deleted > 0) {
            log.info("Purged {} dead invite(s) (revoked/expired)", deleted);
        }
        return deleted;
    }

    @Scheduled(fixedDelayString = "${oak.invites.cleanup-interval:PT6H}")
    @Transactional   // the scheduler enters HERE through the proxy, but purgeInactive() is
    // then a SELF-invocation that bypasses it — the transaction must be opened at this
    // entry point (the RefreshTokenCleanup lesson).
    void scheduledPurge() {
        purgeInactive();
    }
}
