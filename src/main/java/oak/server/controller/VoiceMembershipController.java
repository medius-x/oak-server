package oak.server.controller;

import lombok.RequiredArgsConstructor;
import oak.server.dto.VoiceChannelMembersDto;
import oak.server.websocket.VoiceMembershipTracker;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Snapshot of every voice room's current members. The client seeds the sidebar from this, then
 * applies live deltas from {@code /topic/voice}. Auto-secured by Spring Security (JWT) like the rest
 * of {@code /api/**}; mirrors {@link PresenceController}.
 */
@RestController
@RequestMapping("/api/voice")
@RequiredArgsConstructor
public class VoiceMembershipController {

    private final VoiceMembershipTracker tracker;

    @GetMapping("/members")
    public List<VoiceChannelMembersDto> members() {
        return tracker.snapshot();
    }
}
