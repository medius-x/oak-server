package oak.server.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

import java.security.Principal;

/**
 * Receives the AFK-activity heartbeat ({@code /app/voice.activity}, no body): the
 * CLIENT knows activity — system-wide input idle under the grace period (using the
 * computer AT ALL, in any app, keeps the member out of AFK) or currently speaking —
 * and reports it while it holds. The handler is a silent no-op for a sender in no
 * voice room (a heartbeat without membership cannot matter — the sweep only consults
 * members of rooms). Must be {@code @Controller} — {@code @MessageMapping} is scanned
 * only on {@code @Controller} beans.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class VoiceActivityHandler {

    private final VoiceMembershipTracker tracker;
    private final VoiceActivityRegistry registry;

    @MessageMapping("/voice.activity")
    public void activity(Principal sender) {
        if (sender == null) {
            return;
        }
        boolean inVoice = tracker.snapshot().stream()
                .anyMatch(room -> room.usernames().contains(sender.getName()));
        if (inVoice) {
            registry.touch(sender.getName());
        }
    }
}
