package oak.server.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import oak.server.repository.ServerProfileRepository;
import oak.server.service.JanusVoiceAccessService;
import org.springframework.stereotype.Component;

/**
 * The AFK room's channel-wide suppression (Discord semantics): EVERY member entering
 * the server's AFK channel — swept there by the idle timeout or joining by hand — is
 * server-muted. The mute rides the tracker's MODERATOR layer (a self-unmute can never
 * lift it; only a moderator with MUTE_MEMBERS can unmute inside the channel, exactly
 * what the user asked for) and is per-channel: leaving the AFK room leaves the mark
 * behind (a later re-entry re-seeds it — the tracker re-publishes the channel's marks
 * on every join), so an ordinary room is never affected.
 *
 * <p>The forced gateway layer ({@code moderateAudio}) runs best-effort on entry — a
 * freshly entering member has no publisher session yet, so the honest client's
 * compliance (driven by the {@code voice.state} push that {@code setMuted} publishes)
 * is what silences the mic; a gateway kick on a rogue re-publisher is a future hardening.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AfkVoicePolicy {

    private final ServerProfileRepository profileRepository;
    private final VoiceMembershipTracker tracker;
    private final JanusVoiceAccessService voiceAccess;

    /** Applies the channel-wide AFK suppression after a member entered {@code channelId}. */
    public void applyOnEnter(String username, Long channelId) {
        Long afkChannelId = profileRepository.singleton().getAfkChannelId();
        if (afkChannelId == null || !afkChannelId.equals(channelId)) {
            return;
        }
        tracker.setMuted(channelId, username, true);
        voiceAccess.moderateAudio(channelId, username, true);
        log.info("[afk] {} suppressed in the AFK channel {}", username, channelId);
    }
}
