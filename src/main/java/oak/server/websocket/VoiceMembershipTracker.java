package oak.server.websocket;

import lombok.RequiredArgsConstructor;
import oak.server.dto.VoiceChannelMembersDto;
import oak.server.dto.VoiceMemberEvent;
import oak.server.dto.VoiceMemberStatus;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    private final SimpMessagingTemplate messagingTemplate;

    private final Map<String, String> sessionToUsername = new HashMap<>();
    private final Map<String, Long> sessionToChannel = new HashMap<>();
    /** channelId &rarr; (username &rarr; open-session count) — a refcount per (channel, user). */
    private final Map<Long, Map<String, Integer>> channelUserCount = new HashMap<>();
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
            toPublish.add(new VoiceMemberEvent(username, channelId, VoiceMemberStatus.LEFT));
        }
    }

    private void publish(VoiceMemberEvent event) {
        messagingTemplate.convertAndSend(VOICE_TOPIC, event);
    }
}
