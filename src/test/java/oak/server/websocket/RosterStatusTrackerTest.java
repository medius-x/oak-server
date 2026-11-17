package oak.server.websocket;

import oak.server.dto.PresenceStatusEvent;
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
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The roster STATUS dimension (the status menu, user request 2026-11-17): explicit
 * per-session verdicts, the STRONGEST-status aggregate (dnd &gt; away &gt; online) over
 * all of a user's sessions, transition-only publishing with a dampening window on the
 * new /topic/roster.status wire PLUS the bridged legacy /topic/voice.afk frames for
 * 0.5.x clients, and the silent flag drop when the last session disconnects (presence
 * OFFLINE tells that story; offline is never a hand-settable status).
 */
class RosterStatusTrackerTest {

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
    private final PresenceTracker presenceTracker = mock(PresenceTracker.class);
    private final SettableClock clock = new SettableClock();
    /** Captured catch-up tasks — run by hand with the clock already advanced. */
    private final List<Runnable> pendingCatchUps = new java.util.ArrayList<>();
    private final RosterStatusTracker tracker = new RosterStatusTracker(
            messagingTemplate, presenceTracker, clock, (task, delayMillis) -> pendingCatchUps.add(task));

    @Test
    void firstAwayVerdictFlipsOnceOnBothWiresAndRepeatsAreSilent() {
        tracker.setStatus("s1", "alice", "away");
        tracker.setStatus("s1", "alice", "away"); // repeat — no second frame

        verify(messagingTemplate, times(1))
                .convertAndSend(eq(RosterStatusTracker.TOPIC), eq(new PresenceStatusEvent("alice", "away")));
        verify(messagingTemplate, times(1))
                .convertAndSend(eq(RosterStatusTracker.LEGACY_AFK_TOPIC), eq(new VoiceAfkStatusEvent("alice", true)));
        assertEquals(Map.of("alice", "away"), tracker.currentStatuses());
    }

    @Test
    void firstOnlineVerdictIsNotNews() {
        // Subscribers assume ONLINE by default, so a fresh connection reporting "I am
        // here" publishes nothing — only the flips are frames.
        tracker.setStatus("s1", "alice", "online");

        verify(messagingTemplate, never()).convertAndSend(eq(RosterStatusTracker.TOPIC), any(PresenceStatusEvent.class));
        assertEquals(Map.of(), tracker.currentStatuses());
    }

    @Test
    void dndFlipsTheStatusWireButNotTheLegacyAfkOne() {
        // dnd is not the away EDGE of the legacy story: 0.5.x clients keep reading
        // the member as present (an accepted degradation until they update).
        tracker.setStatus("s1", "alice", "dnd");

        verify(messagingTemplate, times(1))
                .convertAndSend(eq(RosterStatusTracker.TOPIC), eq(new PresenceStatusEvent("alice", "dnd")));
        verify(messagingTemplate, never())
                .convertAndSend(eq(RosterStatusTracker.LEGACY_AFK_TOPIC), any(VoiceAfkStatusEvent.class));
        assertEquals(Map.of("alice", "dnd"), tracker.currentStatuses());

        clock.advanceMillis(RosterStatusTracker.FLIP_DAMPENING_MILLIS);
        tracker.setStatus("s1", "alice", "online");
        verify(messagingTemplate, times(1))
                .convertAndSend(eq(RosterStatusTracker.TOPIC), eq(new PresenceStatusEvent("alice", "online")));
        verify(messagingTemplate, never())
                .convertAndSend(eq(RosterStatusTracker.LEGACY_AFK_TOPIC), any(VoiceAfkStatusEvent.class));
        assertEquals(Map.of(), tracker.currentStatuses());
    }

    @Test
    void awayToDndMovesOnlyTheStatusWire() {
        tracker.setStatus("s1", "alice", "away");
        clock.advanceMillis(RosterStatusTracker.FLIP_DAMPENING_MILLIS);
        tracker.setStatus("s1", "alice", "dnd");

        // The status wire tells both flips; the legacy wire told its one away-EDGE
        // story and stays silent for away→dnd (still not the away EDGE either way).
        verify(messagingTemplate, times(2)).convertAndSend(eq(RosterStatusTracker.TOPIC), any(PresenceStatusEvent.class));
        verify(messagingTemplate, times(1))
                .convertAndSend(eq(RosterStatusTracker.LEGACY_AFK_TOPIC), eq(new VoiceAfkStatusEvent("alice", true)));
        assertEquals(Map.of("alice", "dnd"), tracker.currentStatuses());
        assertEquals(Set.of(), tracker.currentAfk()); // the legacy pull side agrees
    }

    @Test
    void dndCannotBeOutvotedButAnOnlineDeviceCancelsAnIdleAway() {
        tracker.setStatus("desktop", "alice", "away");
        clock.advanceMillis(RosterStatusTracker.FLIP_DAMPENING_MILLIS);
        tracker.setStatus("phone", "alice", "dnd"); // a manual dnd cannot be outvoted
        assertEquals(Map.of("alice", "dnd"), tracker.currentStatuses());

        clock.advanceMillis(RosterStatusTracker.FLIP_DAMPENING_MILLIS);
        tracker.setStatus("laptop", "alice", "online"); // still dnd — the pin holds
        assertEquals(Map.of("alice", "dnd"), tracker.currentStatuses());

        clock.advanceMillis(RosterStatusTracker.FLIP_DAMPENING_MILLIS);
        tracker.onDisconnect(disconnect("phone")); // the dnd device left — laptop's online cancels desktop's away
        assertEquals(Map.of(), tracker.currentStatuses());
    }

    @Test
    void activeOnAnyDeviceKeepsTheUserPresent() {
        tracker.setStatus("desktop", "alice", "away");
        clock.advanceMillis(RosterStatusTracker.FLIP_DAMPENING_MILLIS);
        tracker.setStatus("laptop", "alice", "online"); // present on one device → present
        assertEquals(Map.of(), tracker.currentStatuses());

        // The active device leaving re-decides the aggregate over the away one.
        clock.advanceMillis(RosterStatusTracker.FLIP_DAMPENING_MILLIS);
        tracker.onDisconnect(disconnect("laptop"));

        ArgumentCaptor<PresenceStatusEvent> events = ArgumentCaptor.forClass(PresenceStatusEvent.class);
        verify(messagingTemplate, times(3)).convertAndSend(eq(RosterStatusTracker.TOPIC), events.capture());
        List<PresenceStatusEvent> frames = events.getAllValues();
        assertEquals("away", frames.get(0).status());   // the desktop went away
        assertEquals("online", frames.get(1).status()); // the laptop being present won
        assertEquals("away", frames.get(2).status());   // the present device left — away again
        assertEquals(Map.of("alice", "away"), tracker.currentStatuses());
    }

    @Test
    void lastSessionDisconnectSilentlyDropsTheFlag() {
        tracker.setStatus("s1", "alice", "away");
        tracker.onDisconnect(disconnect("s1"));
        tracker.onDisconnect(disconnect("s1")); // duplicate disconnect — no-op

        // Exactly one frame ever (the away flip): going fully offline is the presence
        // OFFLINE story, not a status frame.
        verify(messagingTemplate, times(1)).convertAndSend(eq(RosterStatusTracker.TOPIC), any(PresenceStatusEvent.class));
        assertEquals(Map.of(), tracker.currentStatuses());
    }

    @Test
    void dampenedFlipsAreWithheldButACatchUpFrameFollows() {
        tracker.setStatus("s1", "alice", "away"); // t=0 — published

        clock.advanceMillis(100);
        tracker.setStatus("s1", "alice", "online"); // flap inside the window — withheld
        assertEquals(Map.of(), tracker.currentStatuses()); // ...but the live set already says online
        verify(messagingTemplate, times(1)).convertAndSend(eq(RosterStatusTracker.TOPIC), any(PresenceStatusEvent.class));

        // The catch-up fires after the window: the live aggregate is re-told — a
        // short call's dnd→online flip must not stay unsaid for hours (live 2026-11-17).
        clock.advanceMillis(RosterStatusTracker.FLIP_DAMPENING_MILLIS);
        runPendingCatchUps();
        verify(messagingTemplate, times(2)).convertAndSend(eq(RosterStatusTracker.TOPIC), any(PresenceStatusEvent.class));
        ArgumentCaptor<PresenceStatusEvent> events = ArgumentCaptor.forClass(PresenceStatusEvent.class);
        verify(messagingTemplate, times(2)).convertAndSend(eq(RosterStatusTracker.TOPIC), events.capture());
        assertEquals("online", events.getAllValues().get(1).status());
        assertEquals(Map.of(), tracker.currentStatuses());
    }

    /** A short 1:1 call is THE dampened-flip scenario: dnd on answer, online on
     *  hangup seconds later — the roster of the peer must see both. */
    @Test
    void aShortCallDndFlipCatchesUpAfterTheWindow() {
        tracker.setStatus("s1", "alice", "dnd"); // the call started — published
        verify(messagingTemplate, times(1)).convertAndSend(eq(RosterStatusTracker.TOPIC), any(PresenceStatusEvent.class));

        clock.advanceMillis(1_700); // the call lasted under the window
        tracker.setStatus("s1", "alice", "online"); // hangup — withheld
        verify(messagingTemplate, times(1)).convertAndSend(eq(RosterStatusTracker.TOPIC), any(PresenceStatusEvent.class));

        clock.advanceMillis(RosterStatusTracker.FLIP_DAMPENING_MILLIS);
        runPendingCatchUps();
        ArgumentCaptor<PresenceStatusEvent> events = ArgumentCaptor.forClass(PresenceStatusEvent.class);
        verify(messagingTemplate, times(2)).convertAndSend(eq(RosterStatusTracker.TOPIC), events.capture());
        assertEquals("online", events.getAllValues().get(1).status());
    }

    private void runPendingCatchUps() {
        List<Runnable> toRun = List.copyOf(pendingCatchUps);
        pendingCatchUps.clear();
        toRun.forEach(Runnable::run);
    }

    @Test
    void theLegacyBooleanBridgeRidesTheSameModel() {
        tracker.setAway("s1", "alice", true);
        verify(messagingTemplate, times(1))
                .convertAndSend(eq(RosterStatusTracker.TOPIC), eq(new PresenceStatusEvent("alice", "away")));
        assertEquals(Set.of("alice"), tracker.currentAfk());

        clock.advanceMillis(RosterStatusTracker.FLIP_DAMPENING_MILLIS);
        tracker.setAway("s1", "alice", false);
        assertEquals(Set.of(), tracker.currentAfk());
    }

    @Test
    void invisibleMasksPresenceAndPublishesNoStatusFrames() {
        tracker.setStatus("s1", "alice", "away"); // the visible phase: both wires told
        clock.advanceMillis(RosterStatusTracker.FLIP_DAMPENING_MILLIS);
        verify(messagingTemplate, times(1)).convertAndSend(eq(RosterStatusTracker.TOPIC), any(PresenceStatusEvent.class));
        verify(messagingTemplate, times(1)).convertAndSend(eq(RosterStatusTracker.LEGACY_AFK_TOPIC), any(VoiceAfkStatusEvent.class));

        tracker.setStatus("s1", "alice", "online", true); // the mask goes on
        verify(presenceTracker).setMasked("alice", true);
        verify(messagingTemplate, times(1)).convertAndSend(eq(RosterStatusTracker.TOPIC), any(PresenceStatusEvent.class)); // still one
        verify(messagingTemplate, times(1)).convertAndSend(eq(RosterStatusTracker.LEGACY_AFK_TOPIC), any(VoiceAfkStatusEvent.class)); // still one
        assertEquals(Map.of(), tracker.currentStatuses()); // the live sets drop her
        assertEquals(Set.of(), tracker.currentAfk());

        clock.advanceMillis(RosterStatusTracker.FLIP_DAMPENING_MILLIS);
        tracker.setStatus("s1", "alice", "away", true); // status under the mask — silent
        verify(messagingTemplate, times(1)).convertAndSend(eq(RosterStatusTracker.TOPIC), any(PresenceStatusEvent.class));

        clock.advanceMillis(RosterStatusTracker.FLIP_DAMPENING_MILLIS);
        tracker.setStatus("s1", "alice", "away", false); // unmask — the away status tells the world at once
        verify(presenceTracker).setMasked("alice", false);
        verify(messagingTemplate, times(2)).convertAndSend(eq(RosterStatusTracker.TOPIC), any(PresenceStatusEvent.class));
        verify(messagingTemplate, times(2)).convertAndSend(eq(RosterStatusTracker.LEGACY_AFK_TOPIC), any(VoiceAfkStatusEvent.class));
        assertEquals(Map.of("alice", "away"), tracker.currentStatuses());
    }

    @Test
    void oneInvisibleDeviceKeepsTheUserMaskedEvenAgainstAnotherOnlineReport() {
        tracker.setStatus("desktop", "alice", "away");
        clock.advanceMillis(RosterStatusTracker.FLIP_DAMPENING_MILLIS);
        tracker.setStatus("phone", "alice", "online", true);
        verify(presenceTracker).setMasked("alice", true); // the private pick is not outvoted

        clock.advanceMillis(RosterStatusTracker.FLIP_DAMPENING_MILLIS);
        tracker.onDisconnect(disconnect("phone")); // the masked device left — back to visible away
        verify(presenceTracker).setMasked("alice", false);
        assertEquals(Map.of("alice", "away"), tracker.currentStatuses());
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
