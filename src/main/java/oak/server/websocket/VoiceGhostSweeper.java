package oak.server.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import oak.server.dto.VoiceChannelMembersDto;
import oak.server.service.JanusVoiceAccessService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The ghost sweep (H9, security round 4): periodically probes every OCCUPIED voice room for
 * publishers whose {@code display} is none of the room's known live members (STOMP tracker ∪
 * token registry) and asks the gateway to kick them by publisher id. This is the only
 * countermeasure against display spoofing — the forced moderation layer matches publishers by
 * the client-chosen display, so a garbage-display rogue would otherwise be permanently
 * un-mutable, and a copied display impersonates the victim in every UI that keys feeds by name.
 *
 * <p>Idle rooms cost nothing (the union of both registries is walked in memory); an occupied
 * room costs one passive control join per tick. The tick is deliberately slower than the AFK
 * sweep: a ghost survives at most one tick, not zero — instant removal still belongs to the
 * moderator actions ({@code kickUnknownPublishers} runs inside them too).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VoiceGhostSweeper {

    private final VoiceMembershipTracker tracker;
    private final JanusVoiceAccessService voiceAccess;

    /** How often occupied rooms are probed for ghost publishers. */
    @Scheduled(fixedDelayString = "${oak.voice.ghost-sweep-interval:PT60S}")
    public void sweep() {
        Map<Long, Set<String>> knownByRoom = occupiedRooms();
        for (Map.Entry<Long, Set<String>> room : knownByRoom.entrySet()) {
            if (!room.getValue().isEmpty()) {
                voiceAccess.kickUnknownPublishers(room.getKey(), room.getValue());
            }
        }
    }

    /** Union of the two occupancy registries: channelId → known usernames. Package-private
     *  pure shape for the unit test. */
    Map<Long, Set<String>> occupiedRooms() {
        Map<Long, Set<String>> byRoom = new HashMap<>();
        for (VoiceChannelMembersDto room : tracker.snapshot()) {
            byRoom.put(room.channelId(), new HashSet<>(room.usernames()));
        }
        for (Map.Entry<Long, List<String>> room : voiceAccess.tokenHoldersByChannel().entrySet()) {
            byRoom.computeIfAbsent(room.getKey(), ignored -> new HashSet<>())
                    .addAll(room.getValue());
        }
        return byRoom;
    }
}
