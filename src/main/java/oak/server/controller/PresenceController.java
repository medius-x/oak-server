package oak.server.controller;

import lombok.RequiredArgsConstructor;
import oak.server.dto.PresenceActivityEvent;
import oak.server.dto.PresenceStatusSnapshot;
import oak.server.websocket.PresenceActivityTracker;
import oak.server.websocket.PresenceTracker;
import oak.server.websocket.RosterStatusTracker;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Snapshot of currently-online usernames. The client cross-references this with
 * {@code GET /api/users} to render the roster, then applies live deltas from
 * {@code /topic/presence}. Auto-secured by Spring Security (every path outside
 * {@code /api/auth/**} requires a valid JWT).
 */
@RestController
@RequestMapping("/api/presence")
@RequiredArgsConstructor
public class PresenceController {

    private final PresenceTracker presenceTracker;
    private final PresenceActivityTracker activityTracker;
    private final RosterStatusTracker rosterStatusTracker;

    @GetMapping
    public List<String> onlineUsernames() {
        return presenceTracker.onlineUsernames().stream().sorted().toList();
    }

    /**
     * Every online user's CURRENT activity line (users with a line only) — the roster's
     * activity seed; live deltas ride {@code /topic/presence.activity}. The user-set
     * activity is a transit presence state, so an unauthenticated caller gets nothing.
     */
    @GetMapping("/activities")
    public List<PresenceActivityEvent> activities() {
        return activityTracker.currentActivities();
    }

    /**
     * The STATUS dimension's seed (the status menu, user request 2026-11-17): every
     * online user whose client reported a non-online status — {@code away} or
     * {@code dnd}. Live deltas ride {@code /topic/roster.status}; presence-class
     * data (exactly like the online set itself), so no VIEW filtering and an
     * unauthenticated caller gets nothing.
     */
    @GetMapping("/status")
    public PresenceStatusSnapshot statuses() {
        Map<String, String> statuses = rosterStatusTracker.currentStatuses();
        return new PresenceStatusSnapshot(
                statuses.entrySet().stream()
                        .filter(entry -> RosterStatusTracker.AWAY.equals(entry.getValue()))
                        .map(Map.Entry::getKey).sorted().toList(),
                statuses.entrySet().stream()
                        .filter(entry -> RosterStatusTracker.DND.equals(entry.getValue()))
                        .map(Map.Entry::getKey).sorted().toList());
    }
}
