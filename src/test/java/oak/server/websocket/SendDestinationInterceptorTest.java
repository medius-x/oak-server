package oak.server.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.security.Principal;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class SendDestinationInterceptorTest {

    private final SendDestinationInterceptor interceptor = new SendDestinationInterceptor();

    @Test
    void sendToAppDestinationPassesThrough() {
        Message<?> frame = stompMessage(StompCommand.SEND, "/app/call.signal", null);
        assertSame(frame, interceptor.preSend(frame, null));
    }

    @Test
    void sendToTopicDestinationIsDropped() {
        // The core injection: a hand-crafted MessageResponse into any channel topic.
        assertNull(interceptor.preSend(stompMessage(StompCommand.SEND, "/topic/channel.5", "alice"), null));
    }

    @Test
    void sendToAnotherUsersQueueIsDropped() {
        // The per-user queue variant: forged RING/member.kick/voice.control pushes.
        assertNull(interceptor.preSend(
                stompMessage(StompCommand.SEND, "/user/bob/queue/call.signal", "alice"), null));
    }

    @Test
    void sendToBareQueueDestinationIsDropped() {
        assertNull(interceptor.preSend(stompMessage(StompCommand.SEND, "/queue/call.signal", "alice"), null));
    }

    @Test
    void sendWithoutDestinationIsDropped() {
        assertNull(interceptor.preSend(stompMessage(StompCommand.SEND, null, "alice"), null));
    }

    @Test
    void bareAppDestinationWithoutHandlerIsDroppedToo() {
        // "/app" alone routes nowhere; only "/app/..." prefixes are legitimate.
        assertNull(interceptor.preSend(stompMessage(StompCommand.SEND, "/app", "alice"), null));
    }

    @Test
    void nonSendCommandsPassThroughUnchanged() {
        // CONNECT is authenticated by AuthChannelInterceptor, SUBSCRIBE gated by
        // ChannelSubscriptionInterceptor — not this one's business.
        Message<?> subscribe = stompMessage(StompCommand.SUBSCRIBE, "/topic/channel.5", "alice");
        assertSame(subscribe, interceptor.preSend(subscribe, null));
        Message<?> connect = stompMessage(StompCommand.CONNECT, null, null);
        assertSame(connect, interceptor.preSend(connect, null));
    }

    @Test
    void sendToAppTopicOfADiscussionFamilyMemberStillPasses() {
        // Defensive shape check: everything the real client sends lives under /app/.
        assertNotNull(interceptor.preSend(stompMessage(StompCommand.SEND, "/app/voice.join", "alice"), null));
    }

    private static Message<?> stompMessage(StompCommand command, String destination, String username) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setLeaveMutable(true);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        if (username != null) {
            accessor.setUser(new Principal() {
                @Override
                public String getName() {
                    return username;
                }
            });
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
