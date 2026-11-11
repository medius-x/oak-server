package oak.server.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import oak.server.dto.ShareSourceStateEvent;
import oak.server.dto.ShareSourceStateReport;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code SEND /app/share.source-state} → {@code /topic/share.state} (2026-11-11):
 * a publisher's share-source flip (minimized ↔ restored). A minimized shared
 * window keeps publishing a FROZEN frame (WGC) — this relay lets the viewers
 * caption it. The username is stamped SERVER-side from the principal (a report
 * can only describe its sender's own share); a sender outside any voice room
 * is a silent no-op (nothing is being shared), and a hostile client cannot
 * flip-spam: one relayed event per session per second.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class ShareSourceStateHandler {

    static final String STATE_TOPIC = "/topic/share.state";

    private final SimpMessagingTemplate messaging;
    private final VoiceMembershipTracker voiceMembership;
    /** Rate limiting state: sessionId → the last accepted flip's millis. */
    private final Map<String, Long> lastFlipAtBySession = new ConcurrentHashMap<>();

    @MessageMapping("/share.source-state")
    public void report(ShareSourceStateReport body, Principal sender, @Header("simpSessionId") String sessionId) {
        if (body == null || body.iconic() == null || sender == null || sessionId == null) {
            return;
        }
        boolean iconic = body.iconic();
        if (!voiceMembership.isInVoice(sender.getName())) {
            log.debug("rejected share source-state from {} (not in a voice room)", sender.getName());
            return;
        }
        long now = System.currentTimeMillis();
        lastFlipAtBySession.values().removeIf(acceptedAt -> now - acceptedAt > 60_000);
        Long previous = lastFlipAtBySession.put(sessionId, now);
        if (previous != null && now - previous < 1_000) {
            return;
        }
        messaging.convertAndSend(STATE_TOPIC, new ShareSourceStateEvent(sender.getName(), iconic));
    }
}
