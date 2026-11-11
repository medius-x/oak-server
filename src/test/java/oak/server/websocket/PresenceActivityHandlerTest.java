package oak.server.websocket;

import oak.server.dto.PresenceActivity;
import oak.server.dto.PresenceActivityReport;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.security.Principal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The activity report endpoint's own discipline: the principal is stamped server-side,
 * invalid bodies (unknown verb, blank/oversized name) are silent no-ops, and a null
 * activity is the CLEAR report that reaches the tracker.
 */
class PresenceActivityHandlerTest {

    private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    private final PresenceActivityTracker tracker = new PresenceActivityTracker(messaging);
    private final PresenceActivityHandler handler = new PresenceActivityHandler(tracker);
    private final Principal principal = new UsernamePasswordAuthenticationToken("alice", "pw");

    @Test
    void aValidReportSetsThePrincipalOwnLine() {
        handler.report(new PresenceActivityReport(new PresenceActivity("playing", "  CS2  ")), principal, "s1");
        // The name is trimmed on the way in.
        assertEquals("CS2", tracker.currentActivities().get(0).activity().name());
        verify(messaging, times(1)).convertAndSend(eq(PresenceActivityTracker.ACTIVITY_TOPIC), any(oak.server.dto.PresenceActivityEvent.class));
    }

    @Test
    void anUnknownVerbIsASilentNoOp() {
        handler.report(new PresenceActivityReport(new PresenceActivity("dancing", "polka")), principal, "s1");
        assertEquals(0, tracker.currentActivities().size());
        verify(messaging, never()).convertAndSend(any(String.class), any(oak.server.dto.PresenceActivityEvent.class));
    }

    @Test
    void theEmptyPrefixCustomKindPassesValidation() {
        handler.report(new PresenceActivityReport(new PresenceActivity("custom", "CS2")), principal, "s1");
        assertEquals("custom", tracker.currentActivities().get(0).activity().type());
    }

    @Test
    void aBlankOrOversizedNameIsASilentNoOp() {
        handler.report(new PresenceActivityReport(new PresenceActivity("playing", "   ")), principal, "s1");
        handler.report(new PresenceActivityReport(new PresenceActivity("playing", "x".repeat(65))), principal, "s1");
        assertEquals(0, tracker.currentActivities().size());
    }

    @Test
    void aNullActivityClearsAndANullBodyIsInert() {
        handler.report(new PresenceActivityReport(new PresenceActivity("playing", "CS2")), principal, "s1");
        handler.report(new PresenceActivityReport(null), principal, "s1");
        assertEquals(0, tracker.currentActivities().size());
        assertNull(tracker.currentActivities().isEmpty() ? null : "nonempty");

        handler.report(null, principal, "s1"); // no body at all — no throw, no state
        assertEquals(0, tracker.currentActivities().size());
    }
}
