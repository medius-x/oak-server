package oak.server.websocket;

import lombok.RequiredArgsConstructor;
import oak.server.dto.VoiceChannelMembersDto;
import oak.server.dto.VoiceControlState;
import oak.server.dto.VoiceMemberEvent;
import oak.server.dto.VoiceMemberStatus;
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
public class VoiceMembershipTracker {

    /** STOMP destination voice-membership deltas are pushed to: {@code /topic/voice}. */
    public static final String VOICE_TOPIC = "/topic/voice";
    /** STOMP destination server-mute/deafen state is pushed to: {@code /topic/voice.state}. */
    public static final String VOICE_STATE_TOPIC = "/topic/voice.state";

    private final SimpMessagingTemplate messagingTemplate;

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
    private final Object lock = new Object();

    /** Records that {@code sessionId} (authenticated as {@code username}) is now in {@code channelId}. */
    public void enter(String sessionId, String username, Long channelId) {
        List<VoiceMemberEvent> toPublish = new ArrayList<>();
        List<String> membersWithState = new ArrayList<>();
        synchronized (lock) {
            Long current = sessionToChannel.get(sessionId);
            if (current != null && current.equals(channelId)) {
                return; // already in this channel — duplicate join is a no-op
            }
            if (current != null) {
                leaveUnderLock(sessionId, toPublish); // switching rooms: emit LEFT for the old one first
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
    }

    /** Records that {@code sessionId} has left whatever room it was in (no-op if it was in none). */
    public void exit(String sessionId) {
        List<VoiceMemberEvent> toPublish = new ArrayList<>();
        synchronized (lock) {
            leaveUnderLock(sessionId, toPublish);
        }
        toPublish.forEach(this::publish);
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

    public boolean isMuted(Long channelId, String username) {
        synchronized (lock) {
            return stateIn(selfMutedByChannel, channelId, username)
                    || stateIn(modMutedByChannel, channelId, username)
                    || stateIn(impliedSelfMutedByChannel, channelId, username)
                    || stateIn(impliedModMutedByChannel, channelId, username);
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
        List<String> sessions = new ArrayList<>();
        synchronized (lock) {
            for (Map.Entry<String, String> entry : sessionToUsername.entrySet()) {
                if (username.equals(entry.getValue()) && channelId.equals(sessionToChannel.get(entry.getKey()))) {
                    sessions.add(entry.getKey());
                }
            }
        }
        sessions.forEach(this::exit); // exit re-acquires the lock + publishes LEFT outside it
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
            state = new VoiceControlState(username, channelId,
                    selfMuted || modMuted, selfDeafened || modDeafened,
                    selfMuted, selfDeafened, modMuted, modDeafened);
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
                || stateIn(impliedModMutedByChannel, channelId, username);
    }

    /** Must run under {@link #lock}: toggles a state set, returning whether it actually changed. */
    private static boolean applyState(Map<Long, Set<String>> byChannel, Long channelId, String username, boolean on) {
        if (on) {
            return byChannel.computeIfAbsent(channelId, channel -> new HashSet<>()).add(username);
        }
        Set<String> set = byChannel.get(channelId);
        return set != null && set.remove(username);
    }

    /** Drops a disconnecting session from its room — the safety net for abrupt loss / {@code System.exit}. */
    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        exit(accessor.getSessionId());
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

    /** Must run under {@link #lock}: decrements the session's room refcount, emits a LEFT only on 1&rarr;0. */
    private void leaveUnderLock(String sessionId, List<VoiceMemberEvent> toPublish) {
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
            // self-mute dies with its deafen), but the MODERATOR state deliberately SURVIVES — implied
            // mute included: a server-deafen outlives the target leaving/rejoining/kick just like an
            // explicit mute (re-published on the next join). Remove from the real (mutable) sets only
            // — Set.of() from getOrDefault is immutable.
            for (Map<Long, Set<String>> byChannel
                    : List.of(selfMutedByChannel, selfDeafenedByChannel, impliedSelfMutedByChannel)) {
                Set<String> usernames = byChannel.get(channelId);
                if (usernames != null) {
                    usernames.remove(username);
                }
            }
            toPublish.add(new VoiceMemberEvent(username, channelId, VoiceMemberStatus.LEFT));
        }
    }

    private void publish(VoiceMemberEvent event) {
        messagingTemplate.convertAndSend(VOICE_TOPIC, event);
    }
}
