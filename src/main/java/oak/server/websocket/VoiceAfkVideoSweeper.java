package oak.server.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import oak.server.dto.VoiceChannelMembersDto;
import oak.server.repository.ServerProfileRepository;
import oak.server.service.JanusVoiceAccessService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The AFK room's video ban, forced (M8, security round 7): periodically passive-probes the
 * configured AFK channel (the ghost sweeper's mechanics — one control join per tick) and
 * whole-kicks every KNOWN member publishing live video there. The honest client stops its
 * own share cooperatively (the AFK designation hides the share button and a live share dies
 * on the flip); this is the rogue half. Audio-only sitters are never touched — the AFK
 * moderator mute is their lot.
 *
 * <p>Idle rooms cost nothing (the occupancy union is read in memory); an occupied AFK room
 * costs one probe per tick. A kicked publisher is NOT revoked out of the room: they may
 * re-join and keep listening (the ban is on video, not presence).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VoiceAfkVideoSweeper {

    private final ServerProfileRepository profileRepository;
    private final VoiceMembershipTracker tracker;
    private final JanusVoiceAccessService voiceAccess;

    /** How often an occupied AFK room is probed for live video publishers. */
    @Scheduled(fixedDelayString = "${oak.voice.afk-video-sweep-interval:PT60S}")
    public void sweep() {
        Long afkChannelId = profileRepository.singleton().getAfkChannelId();
        if (afkChannelId == null) {
            return; // no AFK channel designated — the feature is off
        }
        Set<String> occupants = occupantsOf(afkChannelId);
        if (occupants.isEmpty()) {
            return; // idle room — nothing to probe (the ghost sweeper's cost rule)
        }
        log.debug("[afk-video] probing occupied AFK room {} ({} member(s))", afkChannelId, occupants.size());
        voiceAccess.kickVideoPublishers(afkChannelId, occupants);
    }

    /** Union of the two occupancy registries for one room (the sweeps' known-set shape). */
    Set<String> occupantsOf(Long channelId) {
        Set<String> known = new HashSet<>();
        for (VoiceChannelMembersDto room : tracker.snapshot()) {
            if (channelId.equals(room.channelId())) {
                known.addAll(room.usernames());
            }
        }
        known.addAll(voiceAccess.tokenHoldersByChannel().getOrDefault(channelId, List.of()));
        return known;
    }
}
