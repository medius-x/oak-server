package oak.server.websocket;

import oak.server.dto.ShareCongestionReport;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.security.Principal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The congestion relay's own discipline: a valid report from a CO-PRESENT viewer
 * lands in the PUBLISHER's user queue with the reporter stamped server-side;
 * malformed bodies (no publisher, destination metacharacters, out-of-range
 * loss), self-reports, strangers outside the share's voice room and
 * rate-limit violations are silent no-ops.
 */
class ShareCongestionHandlerTest {

    private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    private final VoiceMembershipTracker voiceMembership = new VoiceMembershipTracker(messaging, mock(oak.server.service.JanusVoiceAccessService.class));
    private final ShareCongestionHandler handler = new ShareCongestionHandler(messaging, voiceMembership);
    private final Principal viewer = new UsernamePasswordAuthenticationToken("alice", "pw");

    /** The share's room: alice watches bob's share in voice channel 7. */
    private void seatedTogether() {
        voiceMembership.enter("vs1", "alice", 7L);
        voiceMembership.enter("ps1", "bob", 7L);
    }

    @Test
    void aValidReportRelaysIntoThePublishersUserQueue() {
        seatedTogether();
        handler.report(new ShareCongestionReport("bob", 1.4, null), viewer, "s1");
        verify(messaging, times(1)).convertAndSendToUser(eq("bob"), eq(ShareCongestionHandler.USER_QUEUE),
                eq(new ShareCongestionReport("bob", 1.4, "alice")));
    }

    @Test
    void malformedBodiesAreSilentNoOps() {
        seatedTogether();
        handler.report(new ShareCongestionReport(null, 1.0, null), viewer, "s1");
        handler.report(new ShareCongestionReport("   ", 1.0, null), viewer, "s1");
        handler.report(new ShareCongestionReport("a/b", 1.0, null), viewer, "s1"); // destination metacharacter
        handler.report(new ShareCongestionReport("bob%", 1.0, null), viewer, "s1");
        handler.report(new ShareCongestionReport("bob", null, null), viewer, "s1");
        handler.report(new ShareCongestionReport("bob", -0.5, null), viewer, "s1");
        handler.report(new ShareCongestionReport("bob", 100.5, null), viewer, "s1");
        handler.report(null, viewer, "s1");
        handler.report(new ShareCongestionReport("bob", 1.0, null), null, "s1");
        verify(messaging, never()).convertAndSendToUser(any(String.class), any(String.class), any(Object.class));
    }

    @Test
    void aSelfReportNeedsNoRelay() {
        seatedTogether();
        handler.report(new ShareCongestionReport("alice", 1.0, null), viewer, "s1");
        verify(messaging, never()).convertAndSendToUser(any(String.class), any(String.class), any(Object.class));
    }

    @Test
    void aStrangerOutsideTheVoicRoomCannotReport() {
        // bob shares in channel 7; alice sits in ANOTHER room (8).
        voiceMembership.enter("vs1", "alice", 8L);
        voiceMembership.enter("ps1", "bob", 7L);
        handler.report(new ShareCongestionReport("bob", 1.0, null), viewer, "s1");
        verify(messaging, never()).convertAndSendToUser(any(String.class), any(String.class), any(Object.class));
    }

    @Test
    void atMostOneRelayedReportPerSessionPerSecond() {
        seatedTogether();
        handler.report(new ShareCongestionReport("bob", 1.0, null), viewer, "s1");
        handler.report(new ShareCongestionReport("bob", 2.0, null), viewer, "s1"); // same session, instant repeat
        handler.report(new ShareCongestionReport("bob", 3.0, null), viewer, "s2"); // another session passes
        verify(messaging, times(1)).convertAndSendToUser(eq("bob"), eq(ShareCongestionHandler.USER_QUEUE),
                eq(new ShareCongestionReport("bob", 1.0, "alice")));
        verify(messaging, times(1)).convertAndSendToUser(eq("bob"), eq(ShareCongestionHandler.USER_QUEUE),
                eq(new ShareCongestionReport("bob", 3.0, "alice")));
    }
}
