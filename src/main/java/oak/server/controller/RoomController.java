package oak.server.controller;

import lombok.RequiredArgsConstructor;
import oak.server.config.JanusProperties;
import oak.server.dto.RoomResponse;
import oak.server.entity.Channel;
import oak.server.entity.Permission;
import oak.server.repository.ChannelRepository;
import oak.server.service.AuthorizationService;
import oak.server.service.JanusAdminException;
import oak.server.service.JanusVoiceAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Hands the client the Janus room info for a voice channel: the REST endpoint URL (from
 * {@code oak.janus.*}), the room ID (= channel ID), a per-visit access {@code token} and the room's
 * join {@code pin} — the gateway runs with {@code token_auth = true} and rooms are pin-protected, so
 * these credentials ARE the voice entrance. The room itself is created here (lazily, idempotently —
 * the client no longer creates it: only the server knows the room's admin {@code secret} needed to
 * moderate it later). Requires {@link Permission#CONNECT} (channel-scoped). JWT-protected.
 */
@RestController
@RequestMapping("/api/channels")
@RequiredArgsConstructor
public class RoomController {

    private final ChannelRepository channelRepository;
    private final JanusProperties janusProperties;
    private final AuthorizationService auth;
    private final JanusVoiceAccessService voiceAccess;

    @GetMapping("/{channelId}/room")
    public RoomResponse room(@PathVariable Long channelId,
                             @AuthenticationPrincipal UserDetails principal) {
        Channel channel = channelRepository.findById(channelId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found"));
        auth.requireInChannel(auth.requireUser(principal.getUsername()), channel, Permission.CONNECT);
        try {
            voiceAccess.ensureRoom(channel.getId());
            String token = voiceAccess.issueVoiceToken(principal.getUsername(), channel.getId());
            return new RoomResponse(janusProperties.clientRestUrl(), channel.getId(),
                    token, voiceAccess.roomPin(channel.getId()));
        } catch (JanusAdminException gatewayFailure) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Voice gateway unavailable");
        }
    }
}
