package oak.server.service;

import oak.server.dto.CreateMessageRequest;
import oak.server.dto.MessageResponse;
import oak.server.entity.Channel;
import oak.server.entity.ChannelType;
import oak.server.entity.Message;
import oak.server.entity.User;
import oak.server.repository.ChannelRepository;
import oak.server.repository.MessageRepository;
import oak.server.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused Mockito test verifying the variant-A push wiring: creating a message
 * persists it AND broadcasts it to the channel's STOMP topic.
 */
@ExtendWith(MockitoExtension.class)
class MessageServiceTest {

    @Mock
    private MessageRepository messageRepository;
    @Mock
    private ChannelRepository channelRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private MessageService messageService;

    @Test
    void createPersistsMessageAndPushesItToChannelTopic() {
        Long channelId = 7L;

        Channel channel = new Channel("general", ChannelType.TEXT);
        channel.setId(channelId);
        User author = new User("alice", "alice@oak.local", "hash");
        Message saved = new Message(channel, author, "hello");
        saved.setId(100L);

        when(channelRepository.findById(channelId)).thenReturn(Optional.of(channel));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(author));
        when(messageRepository.save(any(Message.class))).thenReturn(saved);

        MessageResponse response = messageService.create(channelId, new CreateMessageRequest("hello"), "alice");

        assertEquals(100L, response.id());
        assertEquals(channelId, response.channelId());
        assertEquals("hello", response.content());
        verify(messagingTemplate).convertAndSend(
                eq(MessageService.CHANNEL_TOPIC_PREFIX + channelId), any(MessageResponse.class));
    }
}
