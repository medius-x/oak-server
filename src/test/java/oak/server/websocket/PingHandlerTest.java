package oak.server.websocket;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit coverage of the connection-quality echo: the client's timestamp is relayed verbatim
 * to the SAME user (the sender), and a null principal or null payload is silently ignored —
 * the echo must never crash or fan out to anyone else.
 */
@ExtendWith(MockitoExtension.class)
class PingHandlerTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private PingHandler handler;

    @Test
    void echoesTheTimestampBackToTheSender() {
        handler.ping(1_728_000_000_000L, () -> "alice");

        verify(messagingTemplate).convertAndSendToUser("alice", PingHandler.PING_QUEUE, 1_728_000_000_000L);
    }

    @Test
    void ignoresANullPrincipal() {
        handler.ping(1_728_000_000_000L, null);

        verifyNoInteractions(messagingTemplate);
    }

    @Test
    void ignoresANullPayload() {
        handler.ping(null, () -> "alice");

        verifyNoInteractions(messagingTemplate);
    }
}
