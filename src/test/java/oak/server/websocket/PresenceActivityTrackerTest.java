package oak.server.websocket;

import oak.server.dto.PresenceActivity;
import oak.server.dto.PresenceActivityEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The roster ACTIVITY dimension: explicit per-session reports, the first-live-session
 * aggregate, transition-only publishing with a dampening window, and the silent drop
 * when the last session disconnects — the RosterStatusTracker semantics transplanted onto
 * a map-valued verdict.
 */
class PresenceActivityTrackerTest {

    /** Deterministic dampening window — the tracker's test seam. */
    private static final class SettableClock extends Clock {
        private Instant now = Instant.ofEpochMilli(0);

        void advanceMillis(long millis) {
            now = now.plusMillis(millis);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private static PresenceActivity activity(String type, String name) {
        return new PresenceActivity(type, name);
    }

    /** The LAST of exactly {@code expectedCount} broadcasts so far. */
    private static PresenceActivityEvent published(SimpMessagingTemplate messaging, int expectedCount) {
        ArgumentCaptor<PresenceActivityEvent> captor = ArgumentCaptor.forClass(PresenceActivityEvent.class);
        verify(messaging, times(expectedCount)).convertAndSend(eq(PresenceActivityTracker.ACTIVITY_TOPIC), captor.capture());
        return captor.getAllValues().get(expectedCount - 1);
    }

    @Test
    void firstSetPublishesOnceAndRepeatsAreSilent() {
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        PresenceActivityTracker tracker = new PresenceActivityTracker(messaging, new SettableClock());
        tracker.setActivity("s1", "alice", activity("playing", "CS2"));

        PresenceActivityEvent event = published(messaging, 1);
        assertEquals("alice", event.username());
        assertEquals("CS2", event.activity().name());

        tracker.setActivity("s1", "alice", activity("playing", "CS2")); // same → not news
        verify(messaging, times(1)).convertAndSend(eq(PresenceActivityTracker.ACTIVITY_TOPIC), any(PresenceActivityEvent.class));
        assertEquals(1, tracker.currentActivities().size());
    }

    @Test
    void aChangedLinePublishesTheReplacementAndClearPublishesNull() {
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        SettableClock clock = new SettableClock();
        PresenceActivityTracker tracker = new PresenceActivityTracker(messaging, clock);
        tracker.setActivity("s1", "alice", activity("listening", "radio"));
        clock.advanceMillis(PresenceActivityTracker.FLIP_DAMPENING_MILLIS);

        tracker.setActivity("s1", "alice", activity("playing", "CS2"));
        PresenceActivityEvent replaced = published(messaging, 2); // set + replacement
        assertEquals("CS2", replaced.activity().name());

        clock.advanceMillis(PresenceActivityTracker.FLIP_DAMPENING_MILLIS);
        tracker.setActivity("s1", "alice", null); // the CLEAR report
        PresenceActivityEvent cleared = published(messaging, 3); // ... + clear
        assertEquals("alice", cleared.username());
        assertNull(cleared.activity());
        assertEquals(List.of(), tracker.currentActivities());
    }

    @Test
    void theFirstLiveSessionWithALineWinsDeterministically() {
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        SettableClock clock = new SettableClock();
        PresenceActivityTracker tracker = new PresenceActivityTracker(messaging, clock);
        tracker.setActivity("s1", "alice", activity("playing", "from session one"));

        clock.advanceMillis(PresenceActivityTracker.FLIP_DAMPENING_MILLIS);
        tracker.setActivity("s2", "alice", activity("listening", "from session two"));
        // s2's report is stored, but s1 (the earlier session) still carries the verdict.
        verify(messaging, times(1)).convertAndSend(eq(PresenceActivityTracker.ACTIVITY_TOPIC), any(PresenceActivityEvent.class));
        assertEquals("from session one", tracker.currentActivities().get(0).activity().name());

        // s1 clears → s2's line surfaces and publishes.
        clock.advanceMillis(PresenceActivityTracker.FLIP_DAMPENING_MILLIS);
        tracker.setActivity("s1", "alice", null);
        PresenceActivityEvent surfaced = published(messaging, 2); // the surfacing of s2 line
        assertEquals("from session two", surfaced.activity().name());
    }

    @Test
    void lastSessionDisconnectSilentlyDropsTheLine() {
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        PresenceActivityTracker tracker = new PresenceActivityTracker(messaging, new SettableClock());
        tracker.setActivity("s1", "alice", activity("playing", "CS2"));
        tracker.onDisconnect(disconnect("s1"));

        verify(messaging, times(1)).convertAndSend(eq(PresenceActivityTracker.ACTIVITY_TOPIC), any(PresenceActivityEvent.class)); // the SET only
        assertEquals(List.of(), tracker.currentActivities());
    }

    @Test
    void aSurvivingSessionKeepsTheLineThroughADisconnect() {
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        PresenceActivityTracker tracker = new PresenceActivityTracker(messaging, new SettableClock());
        tracker.setActivity("s1", "alice", activity("playing", "CS2"));
        tracker.setActivity("s2", "alice", activity("playing", "CS2"));

        tracker.onDisconnect(disconnect("s1")); // one session dies, the verdict stands
        verify(messaging, times(1)).convertAndSend(eq(PresenceActivityTracker.ACTIVITY_TOPIC), any(PresenceActivityEvent.class));
        assertEquals(List.of(new PresenceActivityEvent("alice", activity("playing", "CS2"))), tracker.currentActivities());
    }

    @Test
    void dampenedChangesStaySilentButTheSnapshotCarriesTheLiveState() {
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        SettableClock clock = new SettableClock();
        PresenceActivityTracker tracker = new PresenceActivityTracker(messaging, clock);
        tracker.setActivity("s1", "alice", activity("playing", "CS2"));

        tracker.setActivity("s1", "alice", activity("watching", "a film")); // inside the window → dampened
        verify(messaging, times(1)).convertAndSend(eq(PresenceActivityTracker.ACTIVITY_TOPIC), any(PresenceActivityEvent.class));
        assertEquals("a film", tracker.currentActivities().get(0).activity().name()); // live truth
    }

    private static SessionDisconnectEvent disconnect(String sessionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.DISCONNECT);
        accessor.setSessionId(sessionId);
        accessor.setUser(new UsernamePasswordAuthenticationToken("alice", "pw"));
        return new SessionDisconnectEvent(new Object(), MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders()), sessionId, CloseStatus.NORMAL);
    }
}
