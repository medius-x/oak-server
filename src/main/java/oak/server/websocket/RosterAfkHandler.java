package oak.server.websocket;

import lombok.RequiredArgsConstructor;
import oak.server.dto.RosterAfkReport;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

import java.security.Principal;

/**
 * Receives the roster AFK verdict ({@code /app/roster.afk}, body {@link RosterAfkReport}):
 * the CLIENT decides whether its user is away (system-wide input idle; speaking in voice or
 * streaming keeps them present) and reports the FLIPS only — unlike the voice heartbeat
 * ({@link VoiceActivityHandler}) there is no server-side timeout to age out, the roster dot
 * and the voice AFK sweep are separate entities (user request 2026-11-04). The sender is
 * stamped from the authenticated principal; a null body or principal is a silent no-op
 * (the call.signal discipline). Must be {@code @Controller} — {@code @MessageMapping} is
 * scanned only on {@code @Controller} beans.
 */
@Controller
@RequiredArgsConstructor
public class RosterAfkHandler {

    private final RosterAfkTracker tracker;

    @MessageMapping("/roster.afk")
    public void report(RosterAfkReport body,
                       Principal sender,
                       @Header("simpSessionId") String sessionId) {
        if (sender == null || body == null || body.afk() == null) {
            return;
        }
        tracker.setAway(sessionId, sender.getName(), body.afk());
    }
}
