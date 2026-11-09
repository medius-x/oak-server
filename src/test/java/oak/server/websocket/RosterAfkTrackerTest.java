package oak.server.websocket;

import oak.server.dto.VoiceAfkStatusEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;

/**
 * The roster AFK dimension as a client-reported presence flag (user request 2026-11-04,
 * reworked the same day): explicit per-session verdicts, the away aggregate over all of a
 * user's sessions, transition-only publishing with a dampening window, and silent flag
 * drop when the last session disconnects (presence OFFLINE tells that story).
 */
class RosterAfkTrackerTest {

    /** Deterministic dampening window — the tracker's test seam. */
    private static final class SettableClock extends Clock {
        private Instant instant = Instant.ofEpochMilli(0);

        void advanceMillis(long millis) {
            instant = instant.plusMillis(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private final SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
    private final SettableClock clock = new SettableClock();
    private final RosterAfkTracker tracker = new RosterAfkTracker(messagingTemplate, clock);

    @Test
    void firstAwayVerdictFlipsOnceAndRepeatsAreSilent() {
        tracker.setAway("s1", "alice", true);
        tracker.setAway("s1", "alice", true); // repeat — no second frame

        verify(messagingTemplate, times(1))
                .convertAndSend(eq(RosterAfkTracker.TOPIC), eq(new VoiceAfkStatusEvent("alice", true)));
        assertEquals(Set.of("alice"), tracker.currentAfk());
    }

    @Test
    void firstPresentVerdictIsNotNews() {
        // Subscribers assume PRESENT by default, so a fresh connection reporting "I am
        // here" publishes nothing — only the flips are frames.
        tracker.setAway("s1", "alice", false);

        verify(messagingTemplate, never()).convertAndSend(eq(RosterAfkTracker.TOPIC), any(VoiceAfkStatusEvent.class));
        assertEquals(Set.of(), tracker.currentAfk());
    }

    @Test
    void aPresentReportReturnsTheDotToGreen() {
        tracker.setAway("s1", "alice", true);
        clock.advanceMillis(RosterAfkTracker.FLIP_DAMPENING_MILLIS);
        tracker.setAway("s1", "alice", false);

        verify(messagingTemplate, times(1))
                .convertAndSend(eq(RosterAfkTracker.TOPIC), eq(new VoiceAfkStatusEvent("alice", true)));
        verify(messagingTemplate, times(1))
                .convertAndSend(eq(RosterAfkTracker.TOPIC), eq(new VoiceAfkStatusEvent("alice", false)));
        assertEquals(Set.of(), tracker.currentAfk());
    }

    @Test
    void activeOnAnyDeviceKeepsTheUserPresent() {
        tracker.setAway("desktop", "alice", true);
        clock.advanceMillis(RosterAfkTracker.FLIP_DAMPENING_MILLIS);
        tracker.setAway("laptop", "alice", false); // present on one device → present
        assertEquals(Set.of(), tracker.currentAfk());

        // The active device leaving re-decides the aggregate over the away one.
        clock.advanceMillis(RosterAfkTracker.FLIP_DAMPENING_MILLIS);
        tracker.onDisconnect(disconnect("laptop"));

        ArgumentCaptor<VoiceAfkStatusEvent> events = ArgumentCaptor.forClass(VoiceAfkStatusEvent.class);
        verify(messagingTemplate, times(3)).convertAndSend(eq(RosterAfkTracker.TOPIC), events.capture());
        List<VoiceAfkStatusEvent> frames = events.getAllValues();
        assertTrue(frames.get(0).afk());        // the desktop went away
        assertTrue(!frames.get(1).afk());       // the laptop being present won
        assertTrue(frames.get(2).afk());        // the present device left — away again
        assertEquals(Set.of("alice"), tracker.currentAfk());
    }

    @Test
    void lastSessionDisconnectSilentlyDropsTheFlag() {
        tracker.setAway("s1", "alice", true);
        tracker.onDisconnect(disconnect("s1"));
        tracker.onDisconnect(disconnect("s1")); // duplicate disconnect — no-op

        // Exactly one frame ever (the away flip): going fully offline is the presence
        // OFFLINE story, not an afk frame.
        verify(messagingTemplate, times(1)).convertAndSend(eq(RosterAfkTracker.TOPIC), any(VoiceAfkStatusEvent.class));
        assertEquals(Set.of(), tracker.currentAfk());
    }

    @Test
    void dampenedFlipsAreSilentButKeepTheLiveStateTrue() {
        tracker.setAway("s1", "alice", true); // t=0 — published

        clock.advanceMillis(100);
        tracker.setAway("s1", "alice", false); // flap inside the window — withheld
        assertEquals(Set.of(), tracker.currentAfk()); // ...but the live set already says present

        clock.advanceMillis(100);
        tracker.setAway("s1", "alice", true); // flap again — withheld, state back to away
        assertEquals(Set.of("alice"), tracker.currentAfk());

        clock.advanceMillis(RosterAfkTracker.FLIP_DAMPENING_MILLIS);
        tracker.setAway("s1", "alice", false); // outside the window — the flip catches up
        ArgumentCaptor<VoiceAfkStatusEvent> events = ArgumentCaptor.forClass(VoiceAfkStatusEvent.class);
        verify(messagingTemplate, times(2)).convertAndSend(eq(RosterAfkTracker.TOPIC), events.capture());
        List<VoiceAfkStatusEvent> frames = events.getAllValues();
        assertTrue(frames.get(0).afk());
        assertTrue(!frames.get(1).afk());
        assertEquals(Set.of(), tracker.currentAfk());
    }

    // --- helper: a STOMP disconnect frame carrying the session id (PresenceTrackerTest idiom) ---

    private static SessionDisconnectEvent disconnect(String sessionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.DISCONNECT);
        accessor.setLeaveMutable(true);
        accessor.setSessionId(sessionId);
        accessor.setUser(new UsernamePasswordAuthenticationToken("unused", null, List.of()));
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        return new SessionDisconnectEvent(new Object(), message, sessionId, CloseStatus.NORMAL);
    }
}
