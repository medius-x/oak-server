package oak.server.service;

import oak.server.dto.CreateMessageRequest;
import oak.server.dto.EditMessageRequest;
import oak.server.dto.MessageResponse;
import oak.server.entity.Channel;
import oak.server.entity.ChannelType;
import oak.server.entity.Message;
import oak.server.entity.MessageReaction;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    @Mock
    private AuthorizationService auth;
    @Mock
    private MentionParser mentionParser;
    @Mock
    private oak.server.repository.MessageReactionRepository reactionRepository;

    @InjectMocks
    private MessageService messageService;

    @Test
    void createPersistsMessageAndPushesItToChannelTopic() {
        Long channelId = 7L;
        Channel channel = new Channel("general", ChannelType.TEXT);
        channel.setId(channelId);
        User author = new User("alice", "hash");
        Message saved = new Message(channel, author, "hello");
        saved.setId(100L);

        when(channelRepository.findById(channelId)).thenReturn(Optional.of(channel));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(author));
        when(messageRepository.save(any(Message.class))).thenReturn(saved);
        when(mentionParser.parse(any(), any(), any())).thenReturn(java.util.List.of());

        MessageResponse response = messageService.create(channelId, new CreateMessageRequest("hello", null, null), "alice");

        assertEquals(100L, response.id());
        assertEquals(channelId, response.channelId());
        assertEquals("hello", response.content());
        assertEquals(java.util.List.of(), response.mentions());
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
        User author = new User("alice", "hash");
        Message saved = new Message(channel, author, null, "attachments/abc/pic.png", "pic.png");
        saved.setId(101L);

        when(channelRepository.findById(channelId)).thenReturn(Optional.of(channel));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(author));
        when(attachmentUrlSigner.presignedGetUrl("attachments/abc/pic.png")).thenReturn("https://minio/get/pic.png");
        when(messageRepository.save(any(Message.class))).thenReturn(saved);
        when(mentionParser.parse(any(), any(), any())).thenReturn(java.util.List.of());

        MessageResponse response = messageService.create(channelId,
                new CreateMessageRequest(null, "attachments/abc/pic.png", "pic.png"), "alice");

        assertNull(response.content());
        assertEquals("pic.png", response.attachmentFileName());
        assertEquals("https://minio/get/pic.png", response.attachmentUrl());
        verify(attachmentUrlSigner).presignedGetUrl("attachments/abc/pic.png");
        verify(messagingTemplate).convertAndSend(
                eq(MessageService.CHANNEL_TOPIC_PREFIX + channelId), any(MessageResponse.class));
    }

    @Test
    void editSetsEditedAtAndPushesToEditedTopic() {
        Long channelId = 7L;
        Long messageId = 100L;
        Channel channel = new Channel("general", ChannelType.TEXT);
        channel.setId(channelId);
        // The own-message gate reads ids — the author and the actor are the same user with an id.
        User author = new User("alice", "hash");
        author.setId(5L);
        Message existing = new Message(channel, author, "hello");
        existing.setId(messageId);

        when(channelRepository.findById(channelId)).thenReturn(Optional.of(channel));
        when(messageRepository.findByIdAndChannelId(messageId, channelId)).thenReturn(Optional.of(existing));
        when(auth.requireUser("alice")).thenReturn(author);
        when(messageRepository.save(any(Message.class))).thenReturn(existing);
        when(mentionParser.parse(any(), any(), any())).thenReturn(java.util.List.of());
        when(reactionRepository.findByMessageId(messageId)).thenReturn(java.util.List.of());

        MessageResponse response = messageService.edit(
                channelId, messageId, new EditMessageRequest("hello (fixed)"), "alice");

        assertEquals("hello (fixed)", response.content());
        assertEquals("hello (fixed)", existing.getContent());
        assertNotNull(existing.getEditedAt());
        assertNotNull(response.editedAt());
        assertEquals(java.util.List.of(), response.reactions());
        verify(messagingTemplate).convertAndSend(
                eq(MessageService.CHANNEL_TOPIC_PREFIX + channelId + MessageService.EDITED_TOPIC_SUFFIX),
                any(MessageResponse.class));
    }

    @Test
    void deletePushesMessageIdToDeletedTopic() {
        Long channelId = 7L;
        Long messageId = 100L;
        Channel channel = new Channel("general", ChannelType.TEXT);
        channel.setId(channelId);
        User author = new User("alice", "hash");
        author.setId(5L);
        Message existing = new Message(channel, author, "hello");
        existing.setId(messageId);

        when(channelRepository.findById(channelId)).thenReturn(Optional.of(channel));
        when(messageRepository.findByIdAndChannelId(messageId, channelId)).thenReturn(Optional.of(existing));
        when(auth.requireUser("alice")).thenReturn(author);

        messageService.delete(channelId, messageId, "alice");

        // Reaction rows must be cleared before the message delete (no ON DELETE CASCADE).
        verify(reactionRepository).deleteByMessageId(messageId);
        verify(messageRepository).delete(existing);
        verify(messagingTemplate).convertAndSend(
                eq(MessageService.CHANNEL_TOPIC_PREFIX + channelId + MessageService.DELETED_TOPIC_SUFFIX),
                eq(messageId));
    }

    @Test
    void editOthersMessageInDmRequiresAuthorshipNotJustParticipation() {
        // The DM branch of hasInChannel grants every permission to any participant; edit must
        // still refuse a non-author participant.
        Long channelId = 9L;
        Long messageId = 100L;
        Channel dm = new Channel("dm", ChannelType.DM);
        dm.setId(channelId);
        User author = new User("alice", "hash");
        author.setId(5L);
        User peer = new User("bob", "hash");
        peer.setId(6L);
        Message existing = new Message(dm, author, "alice's words");
        existing.setId(messageId);

        when(channelRepository.findById(channelId)).thenReturn(Optional.of(dm));
        when(messageRepository.findByIdAndChannelId(messageId, channelId)).thenReturn(Optional.of(existing));
        when(auth.requireUser("bob")).thenReturn(peer);

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class, () ->
                messageService.edit(channelId, messageId, new EditMessageRequest("bob was here"), "bob"));
        assertEquals(HttpStatus.FORBIDDEN, thrown.getStatusCode());
        verify(messagingTemplate, org.mockito.Mockito.never()).convertAndSend(any(String.class), any(Object.class));
    }

    @Test
    void addReactionPushesSnapshotToReactionsTopic() {
        Long channelId = 7L;
        Long messageId = 100L;
        Channel channel = new Channel("general", ChannelType.TEXT);
        channel.setId(channelId);
        User actor = new User("alice", "hash");
        actor.setId(5L);
        Message existing = new Message(channel, actor, "hello");
        existing.setId(messageId);
        MessageReaction row = new MessageReaction(existing, actor, "thumbsup");

        when(channelRepository.findById(channelId)).thenReturn(Optional.of(channel));
        when(messageRepository.findByIdAndChannelId(messageId, channelId)).thenReturn(Optional.of(existing));
        when(auth.requireUser("alice")).thenReturn(actor);
        when(reactionRepository.existsByMessageIdAndUserIdAndReaction(messageId, 5L, "thumbsup"))
                .thenReturn(false);
        when(reactionRepository.save(any(MessageReaction.class))).thenReturn(row);
        when(reactionRepository.findByMessageId(messageId)).thenReturn(java.util.List.of(row));

        java.util.List<oak.server.dto.ReactionSummary> snapshot =
                messageService.addReaction(channelId, messageId, "thumbsup", "alice");

        assertEquals(1, snapshot.size());
        assertEquals("thumbsup", snapshot.get(0).reaction());
        assertEquals(1, snapshot.get(0).count());
        assertEquals(java.util.List.of(5L), snapshot.get(0).userIds());
        verify(messagingTemplate).convertAndSend(
                eq(MessageService.CHANNEL_TOPIC_PREFIX + channelId + MessageService.REACTIONS_TOPIC_SUFFIX),
                any(oak.server.dto.ReactionEvent.class));
    }

    @Test
    void addReactionUnknownSlugRejectedBeforeAnyWrite() {
        Long channelId = 7L;
        Long messageId = 100L;
        Channel channel = new Channel("general", ChannelType.TEXT);
        channel.setId(channelId);
        User actor = new User("alice", "hash");
        actor.setId(5L);
        Message existing = new Message(channel, actor, "hello");
        existing.setId(messageId);

        when(channelRepository.findById(channelId)).thenReturn(Optional.of(channel));
        when(messageRepository.findByIdAndChannelId(messageId, channelId)).thenReturn(Optional.of(existing));
        when(auth.requireUser("alice")).thenReturn(actor);

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class, () ->
                messageService.addReaction(channelId, messageId, "elephant", "alice"));
        assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatusCode());
        verify(reactionRepository, org.mockito.Mockito.never())
                .save(any(oak.server.entity.MessageReaction.class));
        verify(messagingTemplate, org.mockito.Mockito.never()).convertAndSend(any(String.class), any(Object.class));
    }

    @Test
    void addReactionRequiresAddReactionsInChannel() {
        Long channelId = 7L;
        Long messageId = 100L;
        Channel channel = new Channel("general", ChannelType.TEXT);
        channel.setId(channelId);
        User actor = new User("alice", "hash");
        actor.setId(5L);
        Message existing = new Message(channel, new User("bob", "hash"), "hello");
        existing.setId(messageId);

        when(channelRepository.findById(channelId)).thenReturn(Optional.of(channel));
        when(messageRepository.findByIdAndChannelId(messageId, channelId)).thenReturn(Optional.of(existing));
        when(auth.requireUser("alice")).thenReturn(actor);
        org.mockito.Mockito.doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "denied"))
                .when(auth).requireInChannel(actor, channel, oak.server.entity.Permission.ADD_REACTIONS);

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class, () ->
                messageService.addReaction(channelId, messageId, "thumbsup", "alice"));
        assertEquals(HttpStatus.FORBIDDEN, thrown.getStatusCode());
        verify(reactionRepository, org.mockito.Mockito.never())
                .save(any(oak.server.entity.MessageReaction.class));
    }

    @Test
    void removeReactionMissingRowStillBroadcastsSnapshot() {
        Long channelId = 7L;
        Long messageId = 100L;
        Channel channel = new Channel("general", ChannelType.TEXT);
        channel.setId(channelId);
        User actor = new User("alice", "hash");
        actor.setId(5L);
        Message existing = new Message(channel, actor, "hello");
        existing.setId(messageId);

        when(channelRepository.findById(channelId)).thenReturn(Optional.of(channel));
        when(messageRepository.findByIdAndChannelId(messageId, channelId)).thenReturn(Optional.of(existing));
        when(auth.requireUser("alice")).thenReturn(actor);
        when(reactionRepository.deleteByMessageIdAndUserIdAndReaction(messageId, 5L, "thumbsup")).thenReturn(0L);
        when(reactionRepository.findByMessageId(messageId)).thenReturn(java.util.List.of());

        java.util.List<oak.server.dto.ReactionSummary> snapshot =
                messageService.removeReaction(channelId, messageId, "thumbsup", "alice");

        assertEquals(java.util.List.of(), snapshot);
        verify(messagingTemplate).convertAndSend(
                eq(MessageService.CHANNEL_TOPIC_PREFIX + channelId + MessageService.REACTIONS_TOPIC_SUFFIX),
                any(oak.server.dto.ReactionEvent.class));
    }
}
