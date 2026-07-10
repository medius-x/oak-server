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
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mockito tests for the variant-A push wiring and the content-or-attachment validation, plus the
 * attachment GET-URL enrichment.
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
    @Mock
    private AttachmentUrlSigner attachmentUrlSigner;

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

        MessageResponse response = messageService.create(channelId, new CreateMessageRequest("hello", null, null), "alice");

        assertEquals(100L, response.id());
        assertEquals(channelId, response.channelId());
        assertEquals("hello", response.content());
        assertNull(response.attachmentUrl());
        verify(messagingTemplate).convertAndSend(
                eq(MessageService.CHANNEL_TOPIC_PREFIX + channelId), any(MessageResponse.class));
    }

    @Test
    void createRejectsMessageWithNeitherContentNorAttachment() {
        ResponseStatusException thrown = assertThrows(ResponseStatusException.class, () ->
                messageService.create(7L, new CreateMessageRequest("", null, null), "alice"));
        assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatusCode());
    }

    @Test
    void createAcceptsAttachmentOnlyMessageAndSignsGetUrl() {
        Long channelId = 7L;
        Channel channel = new Channel("general", ChannelType.TEXT);
        channel.setId(channelId);
        User author = new User("alice", "alice@oak.local", "hash");
        Message saved = new Message(channel, author, null, "attachments/abc/pic.png", "pic.png");
        saved.setId(101L);

        when(channelRepository.findById(channelId)).thenReturn(Optional.of(channel));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(author));
        when(attachmentUrlSigner.presignedGetUrl("attachments/abc/pic.png")).thenReturn("https://minio/get/pic.png");
        when(messageRepository.save(any(Message.class))).thenReturn(saved);

        MessageResponse response = messageService.create(channelId,
                new CreateMessageRequest(null, "attachments/abc/pic.png", "pic.png"), "alice");

        assertNull(response.content());
        assertEquals("pic.png", response.attachmentFileName());
        assertEquals("https://minio/get/pic.png", response.attachmentUrl());
        verify(attachmentUrlSigner).presignedGetUrl("attachments/abc/pic.png");
        verify(messagingTemplate).convertAndSend(
                eq(MessageService.CHANNEL_TOPIC_PREFIX + channelId), any(MessageResponse.class));
    }
}
