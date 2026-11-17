package oak.server.websocket;

import lombok.RequiredArgsConstructor;
import oak.server.dto.RosterAfkReport;
import oak.server.dto.RosterStatusReport;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

import java.security.Principal;

/**
 * Receives the roster presence verdicts. The STATUS menu (user request
 * 2026-11-17) rides {@code /app/roster.status} (body {@link RosterStatusReport}):
 * the CLIENT decides the effective status — the auto-mode's idle/voice verdict or
 * a manual pin — and reports the FLIPS only (there is no server-side timeout to
 * age out; the roster dot and the voice AFK sweep are separate entities). The
 * legacy boolean {@code /app/roster.afk} (body {@link RosterAfkReport}) still
 * lives here bridge-style: 0.5.x clients keep working against this server. The
 * sender is stamped from the authenticated principal; a null body or principal,
 * or an unknown status, is a silent no-op (the call.signal discipline). Must be
 * {@code @Controller} — {@code @MessageMapping} is scanned only on
 * {@code @Controller} beans.
 */
@Controller
@RequiredArgsConstructor
public class RosterStatusHandler {

    private final RosterStatusTracker tracker;

    @MessageMapping("/roster.status")
    public void reportStatus(RosterStatusReport body,
                             Principal sender,
                             @Header("simpSessionId") String sessionId) {
        if (sender == null || body == null) {
            return;
        }
        if (RosterStatusTracker.ONLINE.equals(body.status())
                || RosterStatusTracker.AWAY.equals(body.status())
                || RosterStatusTracker.DND.equals(body.status())) {
            tracker.setStatus(sessionId, sender.getName(), body.status(), Boolean.TRUE.equals(body.invisible()));
        }
    }

    /** The 0.5.x boolean wire, bridged onto the status model. */
    @MessageMapping("/roster.afk")
    public void reportAfk(RosterAfkReport body,
                          Principal sender,
                          @Header("simpSessionId") String sessionId) {
        if (sender == null || body == null || body.afk() == null) {
            return;
        }
        tracker.setAway(sessionId, sender.getName(), body.afk());
    }
}
