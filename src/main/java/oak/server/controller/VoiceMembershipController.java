package oak.server.controller;

import lombok.RequiredArgsConstructor;
import oak.server.dto.VoiceChannelMembersDto;
import oak.server.entity.Channel;
import oak.server.entity.Permission;
import oak.server.entity.User;
import oak.server.repository.ChannelRepository;
import oak.server.service.AuthorizationService;
import oak.server.websocket.VoiceMembershipTracker;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * Snapshot of every voice room's current members, VIEWER-FILTERED (security round 5, M3): a
 * room the caller cannot even see (hidden via {@code @everyone}-deny VIEW) is omitted — the
 * viewer-filtered channel list deliberately hides it, and an unfiltered snapshot was handing
 * out its occupancy (who sits in the private room, join/leave timing) to any member by one
 * GET. Live deltas on {@code /topic/voice} remain broadcast (simple-broker limitation,
 * documented residual); this closes at least the pull side. Auto-secured by Spring Security
 * (JWT) like the rest of {@code /api/**}.
 */
@RestController
@RequestMapping("/api/voice")
@RequiredArgsConstructor
public class VoiceMembershipController {

    private final VoiceMembershipTracker tracker;
    private final AuthorizationService auth;
    private final ChannelRepository channelRepository;

    @GetMapping("/members")
    public List<VoiceChannelMembersDto> members(@AuthenticationPrincipal UserDetails principal) {
        User viewer = auth.findUser(principal.getUsername()).orElse(null);
        List<VoiceChannelMembersDto> visible = new ArrayList<>();
        for (VoiceChannelMembersDto room : tracker.snapshot()) {
            Channel channel = channelRepository.findById(room.channelId()).orElse(null);
            // A room without a channel row (deleted mid-sweep) is nobody's to see either.
            if (channel != null && viewer != null
                    && auth.hasInChannel(viewer, channel, Permission.VIEW_CHANNEL)) {
                visible.add(room);
            }
        }
        return visible;
    }
}
