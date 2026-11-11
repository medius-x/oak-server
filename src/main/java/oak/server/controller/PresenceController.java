package oak.server.controller;

import lombok.RequiredArgsConstructor;
import oak.server.dto.PresenceActivityEvent;
import oak.server.websocket.PresenceActivityTracker;
import oak.server.websocket.PresenceTracker;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
}
