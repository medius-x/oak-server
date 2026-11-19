package oak.server.service;

import lombok.extern.slf4j.Slf4j;
import oak.server.dto.UserCameOnlineEvent;
import oak.server.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Stamps {@code users.last_seen_at} (V22, 2026-11-19 — the future achievements'
 * "was here" data, collected "just in case") whenever the presence tracker
 * reports a user actually showing up online. The write is MONOTONIC (the
 * repository query never moves a newer stamp backwards) and deliberately a
 * direct UPDATE — an entity save would bump the {@code @UpdateTimestamp}
 * column, which other code reads as a profile change. A failed write (a raced
 * user deletion) is logged and forgotten: presence must never depend on it.
 */
@Slf4j
@Service
public class LastSeenRecorder {

    private final UserRepository users;
    private final Clock clock;

    @Autowired
    public LastSeenRecorder(UserRepository users) {
        this(users, Clock.systemUTC());
    }

    /** Test seam: an injected clock drives the stamp. */
    LastSeenRecorder(UserRepository users, Clock clock) {
        this.users = users;
        this.clock = clock;
    }

    @EventListener
    @Transactional
    public void onCameOnline(UserCameOnlineEvent event) {
        Instant at = Instant.now(clock);
        try {
            users.touchLastSeen(event.username(), at);
        } catch (RuntimeException failure) {
            log.debug("last_seen stamp failed for {}: {}", event.username(), failure.getMessage());
        }
    }
}
