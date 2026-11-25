package oak.server.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The poll deadline sweeper (2026-11-25): once a minute closes every poll whose window has
 * passed and no lazy read happened to touch first, broadcasting the finish so every open card
 * flips to its final-results view. The fixed rate is the GameSessionService sweeper's — the
 * work is one indexed SELECT on the rare open-with-past-deadline set.
 */
@Component
@RequiredArgsConstructor
public class PollSweeper {

    private final PollService pollService;

    @Scheduled(fixedRate = 60_000)
    public void sweep() {
        pollService.sweepExpired();
    }
}
