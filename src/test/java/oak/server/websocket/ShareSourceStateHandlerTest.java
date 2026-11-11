package oak.server.websocket;

import oak.server.dto.ShareSourceStateEvent;
import oak.server.dto.ShareSourceStateReport;
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
 * The source-state relay's own discipline: a flip from a voice-room member is
 * broadcast with the username stamped server-side; senders outside any voice
 * room, malformed bodies and rate-limit violations are silent no-ops.
 */
class ShareSourceStateHandlerTest {

    private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    private final VoiceMembershipTracker voiceMembership = new VoiceMembershipTracker(messaging);
    private final ShareSourceStateHandler handler = new ShareSourceStateHandler(messaging, voiceMembership);
    private final Principal publisher = new UsernamePasswordAuthenticationToken("bob", "pw");

    @Test
    void aFlipFromAVoiceMemberBroadcastsWithTheServerStampedName() {
        voiceMembership.enter("ps1", "bob", 7L);
        handler.report(new ShareSourceStateReport(true), publisher, "s1");
        handler.report(new ShareSourceStateReport(false), publisher, "s1");
        // The rate limit gates the SECOND flip inside one second — only the
        // first lands (a real user flips at human speed; the cap is anti-spam).
        verify(messaging, times(1)).convertAndSend(eq(ShareSourceStateHandler.STATE_TOPIC),
                eq(new ShareSourceStateEvent("bob", true)));
    }

    @Test
    void aSenderOutsideAnyVoiceRoomIsASilentNoOp() {
        handler.report(new ShareSourceStateReport(true), publisher, "s1");
        verify(messaging, never()).convertAndSend(any(String.class), any(Object.class));
    }

    @Test
    void malformedBodiesAreSilentNoOps() {
        voiceMembership.enter("ps1", "bob", 7L);
        handler.report(new ShareSourceStateReport(null), publisher, "s1");
        handler.report(null, publisher, "s1");
        handler.report(new ShareSourceStateReport(true), null, "s1");
        handler.report(new ShareSourceStateReport(true), publisher, null);
        // The tracker's own /topic/voice membership push rides the same mock —
        // the guard is the RELAY topic specifically.
        verify(messaging, never()).convertAndSend(eq(ShareSourceStateHandler.STATE_TOPIC), any(ShareSourceStateEvent.class));
    }
}
