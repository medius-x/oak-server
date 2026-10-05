package oak.server.websocket;

import oak.server.dto.CallSignal;
import oak.server.service.DirectContactGate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.security.Principal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage of {@link CallSignalingHandler}: the M1 recipient gate sits before the relay
 * (a gated frame never reaches the target's queue), a RING counts as a card push, and the
 * relayed frame carries the server-stamped sender.
 */
@ExtendWith(MockitoExtension.class)
class CallSignalingHandlerTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private DirectContactGate contactGate;

    @InjectMocks
    private CallSignalingHandler handler;

    @Test
    void relaysWithTheServerStampedSender() {
        when(contactGate.canReach(eq("alice"), eq("bob"), eq(true))).thenReturn(true);

        handler.relay(new CallSignal("RING", null, "bob", null, null), principal("alice"));

        verify(messagingTemplate).convertAndSendToUser(eq("bob"), eq(CallSignalingHandler.CALL_QUEUE),
                any(CallSignal.class));
    }

    @Test
    void dropsTheFrameWhenTheRecipientIsGated() {
        when(contactGate.canReach(eq("alice"), eq("bob"), eq(true))).thenReturn(false);

        handler.relay(new CallSignal("RING", null, "bob", null, null), principal("alice"));

        verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
    }

    @Test
    void iceFramesAreNotCardPushes() {
        when(contactGate.canReach(eq("alice"), eq("bob"), eq(false))).thenReturn(true);

        handler.relay(new CallSignal("ICE", null, "bob", null,
                        new oak.server.dto.IceCandidate("candidate:1", "0", 0)), principal("alice"));

        verify(messagingTemplate).convertAndSendToUser(eq("bob"), eq(CallSignalingHandler.CALL_QUEUE),
                any(CallSignal.class));
    }

    @Test
    void blankTargetsNeverReachTheGate() {
        handler.relay(new CallSignal("RING", null, "  ", null, null), principal("alice"));
        handler.relay(null, principal("alice"));

        verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
    }

    private static Principal principal(String name) {
        return () -> name;
    }
}
