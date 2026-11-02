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
import java.util.TreeSet;

/**
 * The ghost sweep (H9, security round 4): periodically probes every OCCUPIED voice room for
 * publishers whose {@code display} is none of the room's known live members (STOMP tracker ∪
 * token registry) and asks the gateway to kick them by publisher id. This is the only
 * countermeasure against display spoofing — the forced moderation layer matches publishers by
 * the client-chosen display, so a garbage-display rogue would otherwise be permanently
 * un-mutable, and a copied display impersonates the victim in every UI that keys feeds by name.
 *
 * <p>Idle rooms cost nothing (the union of both registries is walked in memory); an occupied
 * room costs one {@code exists} probe plus one passive control join per tick. The tick is
 * deliberately slower than the AFK sweep: a ghost survives at most one tick, not zero —
 * instant removal still belongs to the moderator actions ({@code kickUnknownPublishers} runs
 * inside them too).
 *
 * <p>Two healings beyond H9 (live report 2026-10-30, a whole day of per-minute WARNs on a
 * production server):
 * <ul>
 *   <li><b>Vanished rooms.</b> The probe runs BEFORE the join: a room the gateway no longer
 *       has (gateway-side destroy our bookkeeping missed) would answer every control join
 *       with janus 426, indistinguishable from a transport hiccup in the join flow — the
 *       sweep now evicts the stale registry members of such a room (a live client re-joins
 *       via /room and the room re-creates) instead of re-failing every tick forever.</li>
 *   <li><b>Offline token TTL.</b> A client that died without a leave keeps its gateway
 *       tokens forever (49 tokens on the reported server; tokens are only revoked on evict
 *       hooks the dead client never triggers). A token holder with no STOMP presence for
 *       {@value #OFFLINE_TOKEN_TICKS} consecutive ticks loses all their tokens — a live
 *       voice session without STOMP is impossible for an honest client (10 s heartbeats),
 *       and a brief reconnect blip re-arms the counter within a tick.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VoiceGhostSweeper {

    private final VoiceMembershipTracker tracker;
    private final JanusVoiceAccessService voiceAccess;
    private final VoiceAccessEnforcer evictor;
    private final PresenceTracker presence;

    /** Consecutive offline ticks before a token holder's tokens are revoked (tick = the sweep interval). */
    private static final int OFFLINE_TOKEN_TICKS = 5;

    /** Per-username count of consecutive ticks spent holding tokens while offline (presence absent). */
    private final Map<String, Integer> offlineTokenTicks = new HashMap<>();

    /** How often occupied rooms are probed for ghost publishers. */
    @Scheduled(fixedDelayString = "${oak.voice.ghost-sweep-interval:PT60S}")
    public void sweep() {
        Map<Long, Set<String>> knownByRoom = occupiedRooms();
        for (Map.Entry<Long, Set<String>> room : knownByRoom.entrySet()) {
            if (room.getValue().isEmpty()) {
                continue;
            }
            if (probeMissing(room.getKey())) {
                healVanishedRoom(room.getKey(), room.getValue());
                continue;
            }
            voiceAccess.kickUnknownPublishers(room.getKey(), room.getValue());
        }
        expireOfflineTokens();
    }

    /**
     * The pre-join {@code exists} probe. A transport failure deliberately reads as
     * "not missing" — a gateway hiccup must never trigger registry healing (the same
     * never-a-false-verdict stance as the pin probe).
     */
    private boolean probeMissing(long channelId) {
        try {
            return voiceAccess.probeRoom(channelId) == JanusVoiceAccessService.RoomProbe.MISSING;
        } catch (RuntimeException probeFailure) {
            log.debug("Room {} probe failed (skipped this tick): {}", channelId, probeFailure.getMessage());
            return false;
        }
    }

    /** Evicts every stale registry member of a room the gateway no longer has. */
    private void healVanishedRoom(long channelId, Set<String> knownUsernames) {
        log.warn("[ghost-sweep] room {} no longer exists on the gateway — evicting stale registry members {} "
                + "(a live client re-joins via /room and the room re-creates)", channelId, knownUsernames);
        for (String username : knownUsernames) {
            evictor.evictFromVanishedRoom(channelId, username, "ghost-sweep");
        }
    }

    /**
     * The offline token TTL: a holder absent from presence accumulates a tick counter; at
     * {@value #OFFLINE_TOKEN_TICKS} ticks every token of theirs is revoked. Holders back
     * online (or whose tokens already left the registry) drop out of the counter.
     */
    private void expireOfflineTokens() {
        Set<String> holders = new TreeSet<>();
        for (List<String> usernames : voiceAccess.tokenHoldersByChannel().values()) {
            holders.addAll(usernames);
        }
        offlineTokenTicks.keySet().retainAll(holders);
        Set<String> online = presence.onlineUsernames();
        for (String username : holders) {
            if (online.contains(username)) {
                offlineTokenTicks.remove(username);
                continue;
            }
            int ticks = offlineTokenTicks.merge(username, 1, Integer::sum);
            if (ticks >= OFFLINE_TOKEN_TICKS) {
                offlineTokenTicks.remove(username);
                log.info("[ghost-sweep] revoking gateway tokens of offline user '{}'", username);
                voiceAccess.revokeTokensForUser(username);
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
