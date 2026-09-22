package oak.server.websocket;

import oak.server.entity.Channel;
import oak.server.entity.ChannelType;
import oak.server.entity.Permission;
import oak.server.entity.User;
import oak.server.repository.ChannelParticipantRepository;
import oak.server.repository.ChannelRepository;
import oak.server.service.AuthorizationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * SUBSCRIBE-gating unit tests over real STOMP frames (the regex + the type branches; the WS
 * transport itself stays a manual-smoke surface — MockMvc has no WS layer). Covers the new
 * {@code .discussions} suffix and a DISCUSSION channel id in the base family (delegated to the
 * parent by the mocked {@code hasInChannel}).
 */
@ExtendWith(MockitoExtension.class)
class ChannelSubscriptionInterceptorTest {

    @Mock
    private ChannelRepository channelRepository;
    @Mock
    private ChannelParticipantRepository participantRepository;
    @Mock
    private AuthorizationService auth;

    @InjectMocks
    private ChannelSubscriptionInterceptor interceptor;

    private Message<?> subscribeFrame(String destination, String username) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        if (username != null) {
            accessor.setUser(() -> username);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    void discussionsSuffixPassesForReadersAndDropsTheRest() {
        Channel parent = new Channel("general", ChannelType.TEXT);
        parent.setId(7L);
        when(channelRepository.findById(7L)).thenReturn(Optional.of(parent));
        User reader = new User("bob", "hash");
        User stranger = new User("eve", "hash");
        when(auth.findUser("bob")).thenReturn(Optional.of(reader));
        when(auth.findUser("eve")).thenReturn(Optional.of(stranger));
        when(auth.hasInChannel(reader, parent, Permission.READ_MESSAGE_HISTORY)).thenReturn(true);
        when(auth.hasInChannel(stranger, parent, Permission.READ_MESSAGE_HISTORY)).thenReturn(false);

        assertNotNull(interceptor.preSend(subscribeFrame("/topic/channel.7.discussions", "bob"), null));
        assertNull(interceptor.preSend(subscribeFrame("/topic/channel.7.discussions", "eve"), null));
    }

    @Test
    void discussionChannelBaseTopicResolvesThroughTheDelegatedCheck() {
        Channel thread = new Channel("discussion-1", ChannelType.DISCUSSION);
        thread.setId(77L);
        when(channelRepository.findById(77L)).thenReturn(Optional.of(thread));
        User reader = new User("bob", "hash");
        User stranger = new User("eve", "hash");
        when(auth.findUser("bob")).thenReturn(Optional.of(reader));
        when(auth.findUser("eve")).thenReturn(Optional.of(stranger));
        when(auth.hasInChannel(reader, thread, Permission.READ_MESSAGE_HISTORY)).thenReturn(true);
        when(auth.hasInChannel(stranger, thread, Permission.READ_MESSAGE_HISTORY)).thenReturn(false);

        assertNotNull(interceptor.preSend(subscribeFrame("/topic/channel.77", "bob"), null));
        assertNull(interceptor.preSend(subscribeFrame("/topic/channel.77.edited", "eve"), null));
    }

    @Test
    void dmFamilyStaysParticipantGated() {
        Channel dm = new Channel("dm", ChannelType.DM);
        dm.setId(50L);
        when(channelRepository.findById(50L)).thenReturn(Optional.of(dm));
        when(participantRepository.isParticipant(50L, "bob")).thenReturn(true);
        when(participantRepository.isParticipant(50L, "eve")).thenReturn(false);

        assertNotNull(interceptor.preSend(subscribeFrame("/topic/channel.50", "bob"), null));
        assertNull(interceptor.preSend(subscribeFrame("/topic/channel.50.reactions", "eve"), null));
    }

    @Test
    void unknownSuffixesAreNotChannelTopicsAndPassThrough() {
        // Not a per-channel family destination — the interceptor must not touch it (public
        // topics like /topic/presence are unrestricted by design).
        assertNotNull(interceptor.preSend(subscribeFrame("/topic/presence", "bob"), null));
        assertNotNull(interceptor.preSend(subscribeFrame("/topic/channel.7.notsuffix", "bob"), null));
    }
}
