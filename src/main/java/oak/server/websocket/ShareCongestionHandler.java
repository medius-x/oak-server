package oak.server.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import oak.server.dto.ShareCongestionReport;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code SEND /app/share.congestion} — the viewer-side congestion relay of the
 * adaptive share-bitrate ladder (2026-11-11): a viewer of user X's screen share
 * reports loss the publisher cannot measure itself; this forwards it into X's
 * user queue. The destination is built SERVER-side from the sanitized publisher
 * name (a report cannot steer deliveries elsewhere); only a CO-PRESENT viewer
 * may report (reporter and publisher sit in the same live voice channel — the
 * share's room); a self-report is dropped; and a hostile client cannot
 * push-spam: one relayed report per session per second, silent no-ops
 * everywhere (the call.signal discipline).
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class ShareCongestionHandler {

    static final String USER_QUEUE = "/queue/share.congestion";

    private final SimpMessagingTemplate messaging;
    private final VoiceMembershipTracker voiceMembership;
    /** Rate limiting state: sessionId → the last accepted report's millis. */
    private final Map<String, Long> lastReportAtBySession = new ConcurrentHashMap<>();

    @MessageMapping("/share.congestion")
    public void report(ShareCongestionReport body, Principal sender, @Header("simpSessionId") String sessionId) {
        if (body == null || sender == null || sessionId == null) {
            return;
        }
        String publisher = sanitizePublisher(body.publisher());
        if (publisher == null || body.lostPct() == null || !Double.isFinite(body.lostPct())
                || body.lostPct() < 0 || body.lostPct() > 100 || publisher.equals(sender.getName())) {
            log.debug("rejected malformed share congestion report from {}", sender.getName());
            return;
        }
        // The audience gate: only a co-present viewer of the share may report
        // on it (both sit in the same live voice channel — the share's room).
        if (!voiceMembership.sharesVoiceRoom(sender.getName(), publisher)) {
            log.debug("rejected share congestion report from {} (not in {}'s voice room)", sender.getName(), publisher);
            return;
        }
        if (!acceptUnderRateLimit(sessionId)) {
            return;
        }
        messaging.convertAndSendToUser(publisher, USER_QUEUE,
                new ShareCongestionReport(publisher, body.lostPct(), sender.getName()));
    }

    /** A plausible target username: non-blank, bounded, no destination metacharacters. */
    private static String sanitizePublisher(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > 64 || trimmed.matches(".*[/:%#].*")) {
            return null;
        }
        return trimmed;
    }

    /** At most one relayed report per session per second (a slow client sends
     *  one per 3 s; a hostile one gets capped here). The map self-cleans. */
    private boolean acceptUnderRateLimit(String sessionId) {
        long now = System.currentTimeMillis();
        lastReportAtBySession.values().removeIf(acceptedAt -> now - acceptedAt > 60_000);
        Long previous = lastReportAtBySession.put(sessionId, now);
        return previous == null || now - previous >= 1_000;
    }
}
