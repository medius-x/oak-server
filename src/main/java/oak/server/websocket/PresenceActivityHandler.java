package oak.server.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import oak.server.dto.PresenceActivity;
import oak.server.dto.PresenceActivityReport;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.Set;

/**
 * {@code SEND /app/presence.activity} — a session's own roster activity line. The
 * principal is stamped server-side (a report can only describe its sender); a body
 * without a principal, or with an activity that fails validation, is a silent no-op —
 * the call.signal discipline: a hostile client must not be able to error-log-spam or
 * broadcast junk through this endpoint.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class PresenceActivityHandler {

    /** The closed verb set (the client localizes each prefix; the server stores the key). */
    static final Set<String> ACTIVITY_TYPES = Set.of(
            "playing", "listening", "watching", "streaming", "competing");

    static final int NAME_MAX_LENGTH = 64;

    private final PresenceActivityTracker tracker;

    @MessageMapping("/presence.activity")
    public void report(PresenceActivityReport body, Principal sender, @Header("simpSessionId") String sessionId) {
        if (body == null || sender == null || sessionId == null) {
            return;
        }
        PresenceActivity activity = sanitize(body.activity());
        if (activity == null && body.activity() != null) {
            log.debug("rejected invalid activity report from {}", sender.getName());
            return;
        }
        tracker.setActivity(sessionId, sender.getName(), activity);
    }

    /** Valid activity, or null; a null input (the CLEAR report) passes through as null. */
    private static PresenceActivity sanitize(PresenceActivity activity) {
        if (activity == null) {
            return null;
        }
        if (activity.type() == null || !ACTIVITY_TYPES.contains(activity.type())) {
            return null;
        }
        String name = activity.name() == null ? "" : activity.name().trim();
        if (name.isEmpty() || name.length() > NAME_MAX_LENGTH) {
            return null;
        }
        return new PresenceActivity(activity.type(), name);
    }
}
