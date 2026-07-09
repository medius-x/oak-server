package oak.server.controller;

import lombok.RequiredArgsConstructor;
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

    @GetMapping
    public List<String> onlineUsernames() {
        return presenceTracker.onlineUsernames().stream().sorted().toList();
    }
}
