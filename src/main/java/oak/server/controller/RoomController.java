package oak.server.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import oak.server.config.JanusProperties;
import oak.server.dto.RoomResponse;
import oak.server.entity.Channel;
import oak.server.entity.Permission;
import oak.server.entity.User;
import oak.server.repository.ChannelRepository;
import oak.server.service.AuthorizationService;
import oak.server.service.JanusAdminException;
import oak.server.service.JanusVoiceAccessService;
import oak.server.websocket.VoiceMembershipTracker;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.Set;

/**
 * Hands the client the Janus room info for a voice channel: the REST endpoint URL (from
 * {@code oak.janus.*}), the room ID (= channel ID), a per-visit access {@code token} and the room's
 * join {@code pin} — the gateway runs with {@code token_auth = true} and rooms are pin-protected, so
 * these credentials ARE the voice entrance. The room itself is created here (lazily, idempotently —
 * the client no longer creates it: only the server knows the room's admin {@code secret} needed to
 * moderate it later). Requires {@link Permission#CONNECT} (channel-scoped). JWT-protected.
 *
 * <p><b>User limit (V18):</b> a VOICE channel with {@code userLimit} refuses entry with 409 while
 * the room holds the limit's worth of OTHER users. Holders of {@link Permission#JOIN_FULL_ROOMS}
 * (channel-scoped, so per-channel overrides can grant it) bypass the count. A requester holding a
 * LIVE room token re-enters freely — a member re-fetching {@code /room} (the rejoin ladder after a
 * gateway hiccup) adds no seat and must never be locked out of a room they are already in. The
 * pass-card is the TOKEN, not tracker membership: the client announces {@code /app/voice.join}
 * before fetching {@code /room}, so a tracker entry proves an announce, not an entry — and the
 * requester's own in-flight announce never counts as a seat (the race would otherwise 409 the very
 * first joiner of an empty limit-1 room). Occupancy is
 * the UNION of the STOMP membership and the live token registry: a member whose STOMP blipped but
 * whose Janus session lives on still holds a token, and cutting THAT talk mid-flight would break
 * the "never kick a live talk" rule. The price is bounded phantom seats — a {@code /room} fetch
 * that never becomes a join holds a seat until the ghost sweep revokes its token (≈3 min). The
 * AFK sweep's server-side moves never pass through here, so they are not limit-gated (system
 * action). Lowering the limit below the occupancy evicts nobody — only new entries are blocked.
 */
@RestController
@RequestMapping("/api/channels")
@RequiredArgsConstructor
@Slf4j
public class RoomController {

    private final ChannelRepository channelRepository;
    private final JanusProperties janusProperties;
    private final AuthorizationService auth;
    private final JanusVoiceAccessService voiceAccess;
    private final VoiceMembershipTracker voiceMembership;

    @GetMapping("/{channelId}/room")
    public RoomResponse room(@PathVariable Long channelId,
                             @AuthenticationPrincipal UserDetails principal) {
        Channel channel = channelRepository.findById(channelId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found"));
        User requester = auth.requireUser(principal.getUsername());
        auth.requireInChannel(requester, channel, Permission.CONNECT);
        requireRoomNotFull(channel, requester, principal.getUsername());
        try {
            voiceAccess.ensureRoom(channel.getId());
            String token = voiceAccess.issueVoiceToken(principal.getUsername(), channel.getId());
            return new RoomResponse(janusProperties.clientRestUrl(), channel.getId(),
                    token, voiceAccess.roomPin(channel.getId()));
        } catch (JanusAdminException gatewayFailure) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Voice gateway unavailable");
        }
    }

    /** The V18 entry gate — see the class javadoc. Runs BEFORE any gateway work: a full room
     *  costs one DB read, not a Janus round-trip. */
    private void requireRoomNotFull(Channel channel, User requester, String username) {
        Integer limit = channel.getUserLimit();
        if (limit == null) {
            return; // unlimited room
        }
        Set<String> tokenHolders = new HashSet<>(
                voiceAccess.tokenHoldersByChannel().getOrDefault(channel.getId(), java.util.List.of()));
        if (tokenHolders.contains(username)) {
            return; // holds a LIVE room token = already inside: a /room re-fetch (the rejoin
            // ladder) adds no seat and must never cut a live talk — even in an over-limit room
            // (the limit was lowered under the seated population). A TRACKER entry alone must
            // NOT pass: the client announces /app/voice.join BEFORE fetching /room, so tracker
            // membership proves an announce, not an entry — the token is minted only by this
            // gate itself (live report 2026-11-18: the announce fast-pass let everyone in).
        }
        if (auth.hasInChannel(requester, channel, Permission.JOIN_FULL_ROOMS)) {
            return; // the bypass — OWNER/ADMINISTRATOR short-circuit applies inside hasInChannel
        }
        // Occupancy = the UNION of both registries (a tracker-only member — announced, or a
        // token-revoked straggler — still socially occupies the seat) MINUS the requester:
        // their own in-flight announce is not a seat. Without the exclusion the announce-vs-
        // /room race would refuse the ENTRANT themselves (an empty limit-1 room would 409 its
        // very first joiner whenever the STOMP frame landed first).
        Set<String> occupants = new HashSet<>(voiceMembership.usernamesIn(channel.getId()));
        occupants.addAll(tokenHolders);
        occupants.remove(username);
        if (occupants.size() >= limit) {
            log.info("[voice] room-{} entry refused for '{}' — full ({}/{})", channel.getId(), username,
                    occupants.size(), limit);
            // 409, not 403: a full room is a STATE conflict, not a permission denial — the
            // client distinguishes it from a revoked CONNECT and toasts "room full" (an old
            // client just shows its generic connect failure, the accepted degradation).
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Voice room is full");
        }
    }
}
