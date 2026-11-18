package oak.server.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import oak.server.dto.VoiceChannelMembersDto;
import oak.server.dto.VoiceControlState;
import oak.server.dto.VoiceMemberEvent;
import oak.server.dto.VoiceMemberStatus;
import oak.server.service.JanusVoiceAccessService;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * In-memory registry of who is currently in each voice room. Mirrors {@link PresenceTracker}: a user
 * may hold several STOMP sessions (multiple tabs), and is shown as a member of a room while at least
 * one session is in it. Only the 0&rarr;1 and 1&rarr;0 transitions <em>per (channel, username)</em> push
 * a {@link VoiceMemberEvent} to {@link #VOICE_TOPIC}.
 *
 * <p>Clients signal joins/leaves with STOMP SENDs to {@code /app/voice.join} / {@code /app/voice.leave}
 * ({@link VoiceMembershipHandler}); the handler passes the STOMP session id so that, on disconnect,
 * this tracker drops the session from its room exactly like presence goes OFFLINE — no reliance on a
 * best-effort leave surviving an abrupt close or {@code System.exit}.
 *
 * <p><b>Idempotency:</b> a duplicate join for the same (session, channel) is a no-op, and a second
 * exit/disconnect for a session is a no-op (removal keyed by session id). Boundary decisions are made
 * under a single lock; publishing happens outside it so broker dispatch never blocks the registry.
 *
 * <p><b>Mute/deafen state is split by origin</b> (self toggle vs moderator action): a self-unmute
 * must never lift a moderator's mute (Discord's guild-mute/self-mute distinction), so the two live
 * in separate sets while {@link #isMuted}/{@link #isDeafened} and the {@link VoiceControlState}
 * broadcast expose the UNION — the wire shape is unchanged.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VoiceMembershipTracker {

    /** STOMP destination voice-membership deltas are pushed to: {@code /topic/voice}. */
    public static final String VOICE_TOPIC = "/topic/voice";
    /** STOMP destination server-mute/deafen state is pushed to: {@code /topic/voice.state}. */
    public static final String VOICE_STATE_TOPIC = "/topic/voice.state";

    private final SimpMessagingTemplate messagingTemplate;
    /** Immediate token revocation on the EXPLICIT leave paths (2026-11-18): leaving a room
     *  used to leave the gateway token alive for the ghost sweep's grace — an honest
     *  exit then produced a three-minute INFO/WARN log tail. Revoking at the leave kills
     *  the token at once and quietly; the sweep's invisible-holder grace stays as the
     *  anomaly backstop (a DISCONNECT deliberately does NOT revoke — a live voice session
     *  can survive a STOMP blip, and the sweep's greces own that path). */
    private final JanusVoiceAccessService voiceAccess;

    private final Map<String, String> sessionToUsername = new HashMap<>();
    private final Map<String, Long> sessionToChannel = new HashMap<>();
    /** channelId &rarr; (username &rarr; open-session count) — a refcount per (channel, user). */
    private final Map<Long, Map<String, Integer>> channelUserCount = new HashMap<>();
    /** Mute/deafen state per origin: channelId &rarr; usernames. Self-applied (the member's own
     *  toggle) and moderator-applied state are kept in SEPARATE sets — a self-unmute must never
     *  lift a moderator's mute; everything published exposes the union. The self sets drop when the
     *  user leaves; the moderator sets SURVIVE (a server-mute outlives a rejoin) and are
     *  re-published on the next join.
     *
     * <p><b>Deafen implies mute</b> (Discord semantics: a deafened member must not keep
     * broadcasting — they cannot hear the room and don't know what their mic leaks). The implied
     * mute lives in its own sets per origin, written and cleared together with the deafen: an
     * EXPLICIT mute set beside the deafen survives the undeafen, and no client needs to remember
     * anything — {@link #isMuted} and the published origin flags expose the union. */
    private final Map<Long, Set<String>> selfMutedByChannel = new HashMap<>();
    private final Map<Long, Set<String>> modMutedByChannel = new HashMap<>();
    private final Map<Long, Set<String>> selfDeafenedByChannel = new HashMap<>();
    private final Map<Long, Set<String>> modDeafenedByChannel = new HashMap<>();
    private final Map<Long, Set<String>> impliedSelfMutedByChannel = new HashMap<>();
    private final Map<Long, Set<String>> impliedModMutedByChannel = new HashMap<>();
    /** The SPEAK-permission mute (security round 6): a member sitting in a channel they may CONNECT
     *  to but not SPEAK in. Unlike a moderator mute it is derived state — written by the join-time
     *  policy and the permission sweeps, never by a control command — so neither a self-unmute nor
     *  a moderator's Unmute lifts it (each writes its own set). It DIES with the leave: the right
     *  is re-derived on the next entry, so a surviving mark would only go stale. */
    private final Map<Long, Set<String>> permMutedByChannel = new HashMap<>();
    private final Object lock = new Object();

    /** Records that {@code sessionId} (authenticated as {@code username}) is now in {@code channelId}. */
    public void enter(String sessionId, String username, Long channelId) {
        List<VoiceMemberEvent> toPublish = new ArrayList<>();
        List<String> membersWithState = new ArrayList<>();
        List<Revocation> toRevoke = new ArrayList<>(); // the old room, when this enter is a switch
        synchronized (lock) {
            Long current = sessionToChannel.get(sessionId);
            if (current != null && current.equals(channelId)) {
                return; // already in this channel — duplicate join is a no-op
            }
            if (current != null) {
                // switching rooms: emit LEFT for the old one first (and drop its token —
                // the switch is explicit, the old room's token has no honest future)
                leaveUnderLock(sessionId, toPublish, toRevoke);
            }
            sessionToUsername.put(sessionId, username);
            sessionToChannel.put(sessionId, channelId);
            int openSessions = channelUserCount
                    .computeIfAbsent(channelId, channel -> new HashMap<>())
                    .merge(username, 1, Integer::sum);
            if (openSessions == 1) {
                toPublish.add(new VoiceMemberEvent(username, channelId, VoiceMemberStatus.JOINED));
            }
            // RE-SEED THE WHOLE CHANNEL'S STATE on every join (live report 2026-09-17: a
            // muted member's badge vanished for the JOINING viewer — voice.state is push-only,
            // so a client that never saw the original push, or wiped its store, has no source
            // of current marks). This subsumes the former entering-user moderator-state
            // republish: every member of THIS channel carrying any mute/deafen flag (the
            // entering user included — their surviving moderator state must re-comply) is
            // published again, refreshing every viewer.
            for (String member : channelUserCount.getOrDefault(channelId, Map.of()).keySet()) {
                if (hasAnyState(channelId, member)) {
                    membersWithState.add(member);
                }
            }
        }
        toPublish.forEach(this::publish);
        membersWithState.forEach(member -> publishStateIfChanged(true, channelId, member));
        toRevoke.forEach(this::revokeLeft);
    }

    /** Records that {@code sessionId} has left whatever room it was in (no-op if it was in none). */
    public void exit(String sessionId) {
        leave(sessionId, true);
    }

    // --- server-side mute / deafen / kick ---

    /** Moderator-mutes (or un-mutes) {@code username} in {@code channelId}; publishes the resulting state. */
    public void setMuted(Long channelId, String username, boolean muted) {
        publishStateIfChanged(applyState(modMutedByChannel, channelId, username, muted), channelId, username);
    }

    /** Self-mute toggle (the member's own panel button): touches ONLY the self set — a self-unmute
     *  must not lift a moderator's mute; the published union is what everyone sees. */
    public void setSelfMuted(Long channelId, String username, boolean muted) {
        publishStateIfChanged(applyState(selfMutedByChannel, channelId, username, muted), channelId, username);
    }

    public void setDeafened(Long channelId, String username, boolean deafened) {
        boolean deafenChanged = applyState(modDeafenedByChannel, channelId, username, deafened);
        // The deafen implies a moderator mute of the same origin — written and cleared together.
        boolean impliedChanged = applyState(impliedModMutedByChannel, channelId, username, deafened);
        publishStateIfChanged(deafenChanged || impliedChanged, channelId, username);
    }

    /** Self-deafen toggle (the member's own panel button) — see {@link #setSelfMuted}. */
    public void setSelfDeafened(Long channelId, String username, boolean deafened) {
        boolean deafenChanged = applyState(selfDeafenedByChannel, channelId, username, deafened);
        boolean impliedChanged = applyState(impliedSelfMutedByChannel, channelId, username, deafened);
        publishStateIfChanged(deafenChanged || impliedChanged, channelId, username);
    }

    /** The SPEAK-permission mute (join-time policy / permission sweep) — see {@link #permMutedByChannel}. */
    public void setPermMuted(Long channelId, String username, boolean muted) {
        publishStateIfChanged(applyState(permMutedByChannel, channelId, username, muted), channelId, username);
    }

    public boolean isPermMuted(Long channelId, String username) {
        synchronized (lock) {
            return stateIn(permMutedByChannel, channelId, username);
        }
    }

    /** True when both usernames sit in the SAME live voice channel (the share
     *  congestion relay's audience gate, 2026-11-11: only a co-present viewer
     *  may report loss on a publisher's share — a random user of the instance
     *  must not be able to push the publisher's adaptive bitrate ladder down). */
    public boolean sharesVoiceRoom(String first, String second) {
        synchronized (lock) {
            for (Map<String, Integer> users : channelUserCount.values()) {
                if (users.getOrDefault(first, 0) > 0 && users.getOrDefault(second, 0) > 0) {
                    return true;
                }
            }
            return false;
        }
    }

    /** True when the username sits in ANY live voice channel (the share
     *  source-state relay's gate, 2026-11-11: a minimized-share flip is only
     *  meaningful from someone actually sharing inside a room). */
    public boolean isInVoice(String username) {
        synchronized (lock) {
            for (Map<String, Integer> users : channelUserCount.values()) {
                if (users.getOrDefault(username, 0) > 0) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Distinct usernames with at least one live session in the channel — the voice room's
     *  occupancy for the user-limit gate ({@code RoomController}): one seat per USER, not per
     *  tab (multi-session users count once). An idle channel answers an empty set. */
    public Set<String> usernamesIn(Long channelId) {
        synchronized (lock) {
            return Set.copyOf(channelUserCount.getOrDefault(channelId, Map.of()).keySet());
        }
    }

    public boolean isMuted(Long channelId, String username) {
        synchronized (lock) {
            return stateIn(selfMutedByChannel, channelId, username)
                    || stateIn(modMutedByChannel, channelId, username)
                    || stateIn(impliedSelfMutedByChannel, channelId, username)
                    || stateIn(impliedModMutedByChannel, channelId, username)
                    || stateIn(permMutedByChannel, channelId, username);
        }
    }

    public boolean isDeafened(Long channelId, String username) {
        synchronized (lock) {
            return stateIn(selfDeafenedByChannel, channelId, username)
                    || stateIn(modDeafenedByChannel, channelId, username);
        }
    }

    /** Force-removes every session of {@code username} from {@code channelId} (a kick): each is exited,
     *  which publishes a {@code LEFT} on the 1&rarr;0 transition exactly like a voluntary leave. */
    public void kick(Long channelId, String username) {
        for (String sessionId : sessionsOf(channelId, username)) {
            exit(sessionId); // exit re-acquires the lock + publishes LEFT outside it
        }
    }

    /**
     * Moves every session of {@code username} from one channel to another (the AFK sweep):
     * each session is re-entered into the target, which publishes {@code LEFT} for the old
     * room and {@code JOINED} for the new one on their 0&harr;1 boundaries and re-seeds the
     * new room's mute/deafen marks (moderator marks survive by design; the self marks died
     * with the leave). A repeat move to the same pair is a no-op ({@code enter}'s duplicate
     * join guard).
     */
    public void move(Long fromChannelId, Long toChannelId, String username) {
        for (String sessionId : sessionsOf(fromChannelId, username)) {
            enter(sessionId, username, toChannelId);
        }
    }

    /** The member's live sessions in a channel — collected under the lock (kick()'s scan). */
    private List<String> sessionsOf(Long channelId, String username) {
        List<String> sessions = new ArrayList<>();
        synchronized (lock) {
            for (Map.Entry<String, String> entry : sessionToUsername.entrySet()) {
                if (username.equals(entry.getValue()) && channelId.equals(sessionToChannel.get(entry.getKey()))) {
                    sessions.add(entry.getKey());
                }
            }
        }
        return sessions;
    }

    /**
     * Lifts EVERY moderator mute in a channel, publishing each lift (the AFK designation
     * leaving the channel — the channel-wide suppression dies with it, including for
     * members NOT currently sitting there, whose surviving mark the join-time re-seed
     * would otherwise resurrect forever). Only the plain mod-mute is touched: implied
     * marks belong to moderator deafens, which this knows nothing about.
     */
    public void clearModeratorMutes(Long channelId) {
        Set<String> muted;
        synchronized (lock) {
            muted = new java.util.HashSet<>(modMutedByChannel.getOrDefault(channelId, Set.of()));
        }
        muted.forEach(username -> setMuted(channelId, username, false));
    }

    private void publishStateIfChanged(boolean changed, Long channelId, String username) {
        VoiceControlState state;
        synchronized (lock) {
            if (!changed) {
                return;
            }
            // Origin flags include the deafen-IMPLIED mute (a deafened member shows both marks, in
            // the deafen's own colour); muted/deafened are the full unions (compliance semantics).
            boolean selfMuted = stateIn(selfMutedByChannel, channelId, username)
                    || stateIn(impliedSelfMutedByChannel, channelId, username);
            boolean modMuted = stateIn(modMutedByChannel, channelId, username)
                    || stateIn(impliedModMutedByChannel, channelId, username);
            boolean selfDeafened = stateIn(selfDeafenedByChannel, channelId, username);
            boolean modDeafened = stateIn(modDeafenedByChannel, channelId, username);
            boolean permMuted = stateIn(permMutedByChannel, channelId, username);
            state = new VoiceControlState(username, channelId,
                    selfMuted || modMuted || permMuted, selfDeafened || modDeafened,
                    selfMuted, selfDeafened, modMuted, modDeafened, permMuted);
        }
        messagingTemplate.convertAndSend(VOICE_STATE_TOPIC, state);
    }

    private static boolean stateIn(Map<Long, Set<String>> byChannel, Long channelId, String username) {
        return byChannel.getOrDefault(channelId, Set.of()).contains(username);
    }

    /** Whether {@code username} carries ANY mute/deafen flag in {@code channelId} (the join-time
     *  re-seed publishes only members that actually carry marks — an all-false state is the
     *  default nobody needs to hear about). */
    private boolean hasAnyState(Long channelId, String username) {
        return stateIn(selfMutedByChannel, channelId, username)
                || stateIn(modMutedByChannel, channelId, username)
                || stateIn(selfDeafenedByChannel, channelId, username)
                || stateIn(modDeafenedByChannel, channelId, username)
                || stateIn(impliedSelfMutedByChannel, channelId, username)
                || stateIn(impliedModMutedByChannel, channelId, username)
                || stateIn(permMutedByChannel, channelId, username);
    }

    /** Must run under {@link #lock}: toggles a state set, returning whether it actually changed. */
    private static boolean applyState(Map<Long, Set<String>> byChannel, Long channelId, String username, boolean on) {
        if (on) {
            return byChannel.computeIfAbsent(channelId, channel -> new HashSet<>()).add(username);
        }
        Set<String> set = byChannel.get(channelId);
        return set != null && set.remove(username);
    }

    /** Drops a disconnecting session from its room — the safety net for abrupt loss / {@code System.exit}.
     *  Deliberately does NOT revoke the room token: a live voice session can outlive a STOMP
     *  blip (the Janus long-poll is a separate socket), and the ghost sweep's greaces own the
     *  truly-abandoned case — an instant revoke here would cut mid-talk voice on every network
     *  hiccup (the "never kick a live talk" rule, 2026-11-18). */
    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        leave(accessor.getSessionId(), false);
    }

    /** The shared leave path: registry update under the lock, publishing and (for the EXPLICIT
     *  leaves only) token revocation after it. */
    private void leave(String sessionId, boolean revokeOnEmpty) {
        List<VoiceMemberEvent> toPublish = new ArrayList<>();
        List<Revocation> toRevoke = new ArrayList<>();
        synchronized (lock) {
            leaveUnderLock(sessionId, toPublish, toRevoke);
        }
        toPublish.forEach(this::publish);
        if (revokeOnEmpty) {
            toRevoke.forEach(this::revokeLeft);
        }
    }

    /** A user fully leaving a room — the token revocation unit. */
    private record Revocation(String username, long channelId) {}

    /** Fires the immediate token revocation for an explicitly left room (quiet — this is the
     *  ordinary exit path, not a security event; the ghost sweep logs the anomalies). */
    private void revokeLeft(Revocation revocation) {
        log.debug("[voice] revoking room-{} tokens of '{}' (explicit leave)", revocation.channelId(), revocation.username());
        voiceAccess.revokeTokens(revocation.channelId(), revocation.username());
    }

    /** Snapshot of every voice room's members, used to seed {@code GET /api/voice/members}. */
    public List<VoiceChannelMembersDto> snapshot() {
        synchronized (lock) {
            List<VoiceChannelMembersDto> rooms = new ArrayList<>();
            for (Map.Entry<Long, Map<String, Integer>> room : channelUserCount.entrySet()) {
                if (room.getValue().isEmpty()) {
                    continue;
                }
                List<String> usernames = List.copyOf(new TreeMap<>(room.getValue()).keySet());
                rooms.add(new VoiceChannelMembersDto(room.getKey(), usernames));
            }
            return rooms;
        }
    }

    /** Must run under {@link #lock}: decrements the session's room refcount, emits a LEFT and
     *  queues a token revocation only on 1&rarr;0 (the caller decides whether to fire it). */
    private void leaveUnderLock(String sessionId, List<VoiceMemberEvent> toPublish, List<Revocation> toRevoke) {
        String username = sessionToUsername.remove(sessionId);
        if (username == null) {
            return; // not in any room (duplicate exit / disconnect) — no-op
        }
        Long channelId = sessionToChannel.remove(sessionId);
        Map<String, Integer> users = channelUserCount.get(channelId);
        int openSessions = users.merge(username, -1, Integer::sum);
        if (openSessions <= 0) {
            users.remove(username);
            if (users.isEmpty()) {
                channelUserCount.remove(channelId);
            }
            // last session gone — the SELF toggles reset (a rejoin starts with devices on; the implied
            // self-mute dies with its deafen), the PERMISSION mute too (the right is re-derived by the
            // join-time policy on the next entry, a surviving mark would only go stale), but the
            // MODERATOR state deliberately SURVIVES — implied mute included: a server-deafen outlives
            // the target leaving/rejoining/kick just like an explicit mute (re-published on the next
            // join). Remove from the real (mutable) sets only — Set.of() from getOrDefault is immutable.
            for (Map<Long, Set<String>> byChannel
                    : List.of(selfMutedByChannel, selfDeafenedByChannel, impliedSelfMutedByChannel,
                            permMutedByChannel)) {
                Set<String> usernames = byChannel.get(channelId);
                if (usernames != null) {
                    usernames.remove(username);
                }
            }
            toPublish.add(new VoiceMemberEvent(username, channelId, VoiceMemberStatus.LEFT));
            toRevoke.add(new Revocation(username, channelId));
        }
    }

    private void publish(VoiceMemberEvent event) {
        messagingTemplate.convertAndSend(VOICE_TOPIC, event);
    }
}
