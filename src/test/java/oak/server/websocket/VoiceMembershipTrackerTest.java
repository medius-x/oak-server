package oak.server.websocket;

import oak.server.dto.VoiceChannelMembersDto;
import oak.server.dto.VoiceMemberEvent;
import oak.server.dto.VoiceMemberStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Mockito unit test for the voice-membership registry. Mirrors the {@code PresenceTrackerTest} pattern
 * (mock {@link SimpMessagingTemplate}, verify pushes via {@link ArgumentCaptor}) and focuses on the
 * refcount/boundary logic and idempotency against duplicate disconnect events.
 */
@ExtendWith(MockitoExtension.class)
class VoiceMembershipTrackerTest {

    private static final String VOICE_TOPIC = VoiceMembershipTracker.VOICE_TOPIC;
    private static final Long CHANNEL_A = 10L;
    private static final Long CHANNEL_B = 20L;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private VoiceMembershipTracker tracker;

    @Test
    void joinThenLeavePublishesJoinedAndLeft() {
        tracker.enter("s1", "alice", CHANNEL_A);
        tracker.exit("s1");

        List<VoiceMemberEvent> events = captureEvents(2);
        assertEquals("alice", events.get(0).username());
        assertEquals(CHANNEL_A, events.get(0).channelId());
        assertEquals(VoiceMemberStatus.JOINED, events.get(0).status());
        assertEquals(VoiceMemberStatus.LEFT, events.get(1).status());
        assertTrue(tracker.snapshot().isEmpty());
    }

    @Test
    void multiSessionSameChannelPublishesOnFirstJoinAndLastLeave() {
        tracker.enter("s1", "alice", CHANNEL_A); // first session → JOINED
        tracker.enter("s2", "alice", CHANNEL_A); // second tab → no event
        tracker.exit("s1");                       // one tab closed → still a member
        tracker.exit("s2");                       // last tab → LEFT

        List<VoiceMemberEvent> events = captureEvents(2);
        assertEquals(VoiceMemberStatus.JOINED, events.get(0).status());
        assertEquals(VoiceMemberStatus.LEFT, events.get(1).status());
        assertTrue(tracker.snapshot().isEmpty());
    }

    @Test
    void duplicateJoinIsNoOp() {
        tracker.enter("s1", "alice", CHANNEL_A);
        tracker.enter("s1", "alice", CHANNEL_A); // same session + channel → no-op

        verify(messagingTemplate, times(1)).convertAndSend(eq(VOICE_TOPIC), any(VoiceMemberEvent.class));
        assertEquals(List.of("alice"), usernamesIn(CHANNEL_A));
    }

    @Test
    void switchingChannelsPublishesLeftThenJoined() {
        tracker.enter("s1", "alice", CHANNEL_A); // JOINED A
        tracker.enter("s1", "alice", CHANNEL_B); // LEFT A + JOINED B

        List<VoiceMemberEvent> events = captureEvents(3);
        assertEquals(VoiceMemberStatus.JOINED, events.get(0).status());
        assertEquals(CHANNEL_A, events.get(0).channelId());
        assertEquals(VoiceMemberStatus.LEFT, events.get(1).status());
        assertEquals(CHANNEL_A, events.get(1).channelId());
        assertEquals(VoiceMemberStatus.JOINED, events.get(2).status());
        assertEquals(CHANNEL_B, events.get(2).channelId());
        assertTrue(usernamesIn(CHANNEL_A).isEmpty());
        assertEquals(List.of("alice"), usernamesIn(CHANNEL_B));
    }

    @Test
    void disconnectRemovesMemberAndPublishesLeftIfLast() {
        tracker.enter("s1", "alice", CHANNEL_A);
        tracker.onDisconnect(disconnect("s1"));

        List<VoiceMemberEvent> events = captureEvents(2);
        assertEquals(VoiceMemberStatus.LEFT, events.get(1).status());
        assertTrue(tracker.snapshot().isEmpty());
    }

    @Test
    void duplicateDisconnectIsIgnored() {
        tracker.enter("s1", "alice", CHANNEL_A);
        tracker.onDisconnect(disconnect("s1"));
        tracker.onDisconnect(disconnect("s1")); // Spring can fire this twice — must be a no-op

        verify(messagingTemplate, times(2)).convertAndSend(eq(VOICE_TOPIC), any(VoiceMemberEvent.class));
        assertTrue(tracker.snapshot().isEmpty());
    }

    @Test
    void snapshotReflectsCurrentMembersSorted() {
        tracker.enter("s3", "carol", CHANNEL_A);
        tracker.enter("s1", "alice", CHANNEL_A);
        tracker.enter("s2", "bob", CHANNEL_B);

        List<VoiceChannelMembersDto> snapshot = tracker.snapshot().stream()
                .sorted(Comparator.comparing(VoiceChannelMembersDto::channelId))
                .toList();
        assertEquals(List.of(
                new VoiceChannelMembersDto(CHANNEL_A, List.of("alice", "carol")),
                new VoiceChannelMembersDto(CHANNEL_B, List.of("bob"))), snapshot);
    }

    // --- helpers ---

    private List<VoiceMemberEvent> captureEvents(int expectedCount) {
        ArgumentCaptor<VoiceMemberEvent> captor = ArgumentCaptor.forClass(VoiceMemberEvent.class);
        verify(messagingTemplate, times(expectedCount)).convertAndSend(eq(VOICE_TOPIC), captor.capture());
        return captor.getAllValues();
    }

    private List<String> usernamesIn(Long channelId) {
        return tracker.snapshot().stream()
                .filter(room -> room.channelId().equals(channelId))
                .map(VoiceChannelMembersDto::usernames)
                .findFirst()
                .orElse(List.of());
    }

    private static SessionDisconnectEvent disconnect(String sessionId) {
        // The tracker keys disconnect handling by session id only, so the user on the frame is
        // irrelevant; "unused" mirrors that it is never read (same convention as PresenceTrackerTest).
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.DISCONNECT);
        accessor.setLeaveMutable(true); // keep headers mutable so session id sticks (as in production)
        accessor.setSessionId(sessionId);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        return new SessionDisconnectEvent(new Object(), message, sessionId, CloseStatus.NORMAL);
    }
}
