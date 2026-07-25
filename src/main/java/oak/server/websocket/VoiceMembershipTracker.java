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
    /** Server-side mute state: channelId &rarr; muted usernames (dropped when the user leaves). */
    private final Map<Long, Set<String>> mutedByChannel = new HashMap<>();
    /** Server-side deafen state: channelId &rarr; deafened usernames. */
    private final Map<Long, Set<String>> deafenedByChannel = new HashMap<>();
    private final Object lock = new Object();

    /** Records that {@code sessionId} (authenticated as {@code username}) is now in {@code channelId}. */
    public void enter(String sessionId, String username, Long channelId) {
        List<VoiceMemberEvent> toPublish = new ArrayList<>();
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
        }
        toPublish.forEach(this::publish);
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

    /** Server-mutes (or un-mutes) {@code username} in {@code channelId}; publishes the resulting state. */
    public void setMuted(Long channelId, String username, boolean muted) {
        publishStateIfChanged(applyState(mutedByChannel, channelId, username, muted), channelId, username);
    }

    public void setDeafened(Long channelId, String username, boolean deafened) {
        publishStateIfChanged(applyState(deafenedByChannel, channelId, username, deafened), channelId, username);
    }

    public boolean isMuted(Long channelId, String username) {
        synchronized (lock) {
            return mutedByChannel.getOrDefault(channelId, Set.of()).contains(username);
        }
    }

    public boolean isDeafened(Long channelId, String username) {
        synchronized (lock) {
            return deafenedByChannel.getOrDefault(channelId, Set.of()).contains(username);
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
            boolean muted = mutedByChannel.getOrDefault(channelId, Set.of()).contains(username);
            boolean deafened = deafenedByChannel.getOrDefault(channelId, Set.of()).contains(username);
            state = new VoiceControlState(username, channelId, muted, deafened);
        }
        messagingTemplate.convertAndSend(VOICE_STATE_TOPIC, state);
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
            // last session gone — drop any server-mute/deafen state for this (channel, user).
            // Remove from the real (mutable) set only — Set.of() from getOrDefault is immutable.
            Set<String> muted = mutedByChannel.get(channelId);
            if (muted != null) {
                muted.remove(username);
            }
            Set<String> deafened = deafenedByChannel.get(channelId);
            if (deafened != null) {
                deafened.remove(username);
            }
            toPublish.add(new VoiceMemberEvent(username, channelId, VoiceMemberStatus.LEFT));
        }
    }

    private void publish(VoiceMemberEvent event) {
        messagingTemplate.convertAndSend(VOICE_TOPIC, event);
    }
}
