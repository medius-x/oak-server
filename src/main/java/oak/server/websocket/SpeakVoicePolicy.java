package oak.server.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import oak.server.entity.Channel;
import oak.server.entity.Permission;
import oak.server.entity.User;
import oak.server.service.AuthorizationService;
import oak.server.service.JanusVoiceAccessService;
import org.springframework.stereotype.Component;

/**
 * The SPEAK-permission gate on room entry (security round 6, audit M4): a member may CONNECT to a
 * voice channel (hear it) without being allowed to SPEAK in it — Discord's muted-stage semantics.
 * The tracker's entry path resolves the right exactly once and writes the derived
 * {@link VoiceMembershipTracker#setPermMuted permission mute}: neither a self-unmute nor a
 * moderator's Unmute lifts it (each writes its own origin set), only a permission grant — the
 * {@link VoiceAccessEnforcer} sweep — does. A member holding SPEAK enters untouched: the mark died
 * with the previous leave, so there is nothing to lift.
 *
 * <p>The forced gateway layer ({@code moderateAudio}) runs best-effort on entry — a freshly
 * entering member has no publisher session yet, so the honest client's compliance (driven by the
 * {@code voice.state} push that {@code setPermMuted} publishes) is what silences the mic; the
 * sweeps and the ghost sweeper cover a rogue re-publisher later.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SpeakVoicePolicy {

    private final AuthorizationService auth;
    private final VoiceMembershipTracker tracker;
    private final JanusVoiceAccessService voiceAccess;

    /** Applies the SPEAK gate after a member entered the channel (CONNECT already checked by the caller). */
    public void applyOnEnter(User member, Channel channel) {
        if (auth.hasInChannel(member, channel, Permission.SPEAK)) {
            return;
        }
        tracker.setPermMuted(channel.getId(), member.getUsername(), true);
        voiceAccess.moderateAudio(channel.getId(), member.getUsername(), true);
        log.info("[speak] {} perm-muted in channel {} (no SPEAK)", member.getUsername(), channel.getId());
    }
}
