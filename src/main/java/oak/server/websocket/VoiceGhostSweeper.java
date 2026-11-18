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
 *   <li><b>Invisible-holder revocation</b> (2026-11-18, the "silent listener" hole): a
 *       rogue can hold a minted token, stay STOMP-online and simply never send
 *       {@code voice.join} — invisible to the membership tiles while listening (and even
 *       publishing: its username sits in the token registry, so the ghost-kick's known-set
 *       spares it). Rule: a token holder absent from the channel's MEMBERSHIP for
 *       {@value #INVISIBLE_TOKEN_TICKS} consecutive ticks has that channel's tokens
 *       revoked. The grace covers every honest window — the /room → voice.join ramp (join
 *       follows within seconds), a STOMP reconnect (the client re-sends voice.join on
 *       resync), a channel switch (leave + join are immediate) — so a live honest session
 *       is never torn down mid-talk. The revocation kills the gateway session itself:
 *       nothing cooperative, nothing the rogue can ignore.</li>
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

    /**
     * Consecutive ticks a channel-token holder may live absent from that channel's MEMBERSHIP
     * before the channel's tokens are revoked (the invisible-holder rule; tick = the sweep
     * interval → up to ~3 minutes of grace for the honest /room→join ramp and STOMP reconnects).
     */
    static final int INVISIBLE_TOKEN_TICKS = 3;

    /** Per-username count of consecutive ticks spent holding tokens while offline (presence absent). */
    private final Map<String, Integer> offlineTokenTicks = new HashMap<>();

    /** (username → channelId) count of consecutive ticks holding a token while absent from that
     *  channel's membership — the invisible-holder counters, cleared on join or revocation. */
    private final Map<String, Map<Long, Integer>> invisibleTokenTicks = new HashMap<>();

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
        expireInvisibleTokens();
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

    /**
     * The invisible-holder rule: for every live (username, channelId) token, a holder absent
     * from that channel's MEMBERSHIP accumulates a tick; at {@value #INVISIBLE_TOKEN_TICKS}
     * the channel's tokens are revoked (the gateway tears the session down — the silent
     * listener goes deaf). Joining the channel (or losing the token) resets the counter.
     *
     * <p><b>Diagnostics.</b> Every revocation of a possibly-honest user must be explainable
     * from the log alone: the FIRST invisible tick logs an INFO marker with the full context
     * (presence verdict, the holder's other membership channels — the two facts that tell a
     * slow join from a stuck leave), a near-grace recovery logs its reset, and the revocation
     * itself logs WARN with the whole story. A false-positive victim therefore leaves a
     * three-line biography: marker → (optional reset) → revocation.
     */
    private void expireInvisibleTokens() {
        Map<Long, Set<String>> membersByChannel = new HashMap<>();
        for (VoiceChannelMembersDto room : tracker.snapshot()) {
            membersByChannel.put(room.channelId(), new HashSet<>(room.usernames()));
        }
        Map<Long, List<String>> holders = voiceAccess.tokenHoldersByChannel();
        // The counters only ever track live tokens — a revocation or token expiry
        // drops them, so a fresh /room starts from a clean slate.
        invisibleTokenTicks.entrySet().removeIf(entry -> {
            Map<Long, Integer> byChannel = entry.getValue();
            byChannel.keySet().removeIf(channelId ->
                    !holders.containsKey(channelId) || !holders.get(channelId).contains(entry.getKey()));
            return byChannel.isEmpty();
        });
        for (Map.Entry<Long, List<String>> room : holders.entrySet()) {
            long channelId = room.getKey();
            Set<String> members = membersByChannel.getOrDefault(channelId, Set.of());
            for (String username : room.getValue()) {
                // NULL-checked lookups throughout — a getOrDefault(..., Map.of()) default
                // threw UnsupportedOperationException on remove() for every holder without
                // a counter (i.e. every VISIBLE participant, every tick — the live
                // "куча UnsupportedOperationException" report 2026-11-18: the immutable
                // map's remove throws even for absent keys).
                Map<Long, Integer> userTicks = invisibleTokenTicks.get(username);
                if (members.contains(username)) {
                    if (userTicks != null) {
                        Integer previousTicks = userTicks.remove(channelId);
                        if (previousTicks != null && previousTicks >= INVISIBLE_TOKEN_TICKS - 1) {
                            // A near-grace recovery: one more absent tick would have revoked —
                            // exactly the borderline case a false-positive report hinges on.
                            log.info("[ghost-sweep] holder '{}' back in room {} membership after {} invisible ticks — counter reset",
                                    username, channelId, previousTicks);
                        }
                    }
                    continue;
                }
                if (userTicks == null) {
                    userTicks = invisibleTokenTicks.computeIfAbsent(username, ignored -> new HashMap<>());
                }
                int ticks = userTicks.merge(channelId, 1, Integer::sum);
                if (ticks == 1) {
                    log.info("[ghost-sweep] holder '{}' ({}) holds a token for room {} but is absent from its "
                                    + "membership (tick 1/{}; holder's membership channels: {}) — revoking in {} more ticks if it stays this way",
                            username, presence.onlineUsernames().contains(username) ? "online" : "OFFLINE",
                            channelId, INVISIBLE_TOKEN_TICKS, membershipChannelsOf(username, membersByChannel),
                            INVISIBLE_TOKEN_TICKS - 1);
                }
                if (ticks >= INVISIBLE_TOKEN_TICKS) {
                    userTicks.remove(channelId);
                    log.warn("[ghost-sweep] revoking room-{} tokens of invisible holder '{}' — absent from membership "
                                    + "for {} ticks, holder is {}, membership channels: {} (an honest client re-joins via /room; "
                                    + "if this was a live talk, look for the holder's tick-1 marker above)",
                            channelId, username, ticks,
                            presence.onlineUsernames().contains(username) ? "online" : "OFFLINE",
                            membershipChannelsOf(username, membersByChannel));
                    voiceAccess.revokeTokens(channelId, username);
                }
            }
        }
    }

    /** The channels the username currently sits in per the membership tracker — the
     *  revocation log's "where are they instead" context. */
    private static Set<Long> membershipChannelsOf(String username, Map<Long, Set<String>> membersByChannel) {
        Set<Long> channels = new TreeSet<>();
        for (Map.Entry<Long, Set<String>> room : membersByChannel.entrySet()) {
            if (room.getValue().contains(username)) {
                channels.add(room.getKey());
            }
        }
        return channels;
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
