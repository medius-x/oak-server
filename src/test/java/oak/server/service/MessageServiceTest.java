package oak.server.service;

import oak.server.dto.CreateMessageRequest;
import oak.server.dto.EditMessageRequest;
import oak.server.dto.ForwardMessageRequest;
import oak.server.dto.MessageResponse;
import oak.server.dto.MentionResponse;
import oak.server.dto.ReplyInfo;
import oak.server.entity.Channel;
import oak.server.entity.ChannelType;
import oak.server.entity.Message;
import oak.server.entity.MessageReaction;
import oak.server.entity.Permission;
import oak.server.entity.ServerProfile;
import oak.server.entity.User;
import oak.server.repository.ChannelRepository;
import oak.server.repository.MessageRepository;
import oak.server.repository.ServerProfileRepository;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    @Mock
    private ServerProfileRepository serverProfileRepository;

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

        MessageResponse response = messageService.create(channelId, new CreateMessageRequest("hello", null, null, null), "alice");

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
                messageService.create(7L, new CreateMessageRequest("", null, null, null), "alice"));
        assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatusCode());
    }

    @Test
    void createReplyQuotesOriginalAndSynthesizesAuthorMention() {
        Long channelId = 7L;
        Channel channel = new Channel("general", ChannelType.TEXT);
        channel.setId(channelId);
        User author = new User("alice", "hash");
        author.setId(5L);
        User targetAuthor = new User("bob", "hash");
        targetAuthor.setId(6L);
        Message original = new Message(channel, targetAuthor, "the original\nsecond line");
        original.setId(90L);
        original.setCreatedAt(java.time.Instant.parse("2026-01-01T10:00:00Z"));

        when(channelRepository.findById(channelId)).thenReturn(Optional.of(channel));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(author));
        when(messageRepository.findByIdAndChannelId(90L, channelId)).thenReturn(Optional.of(original));
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));
        when(mentionParser.parse(any(), any(), any())).thenReturn(java.util.List.of());

        MessageResponse response = messageService.create(channelId,
                new CreateMessageRequest("a reply", null, null, 90L), "alice");

        // The quote: live original's author + flattened one-line preview.
        ReplyInfo reply = response.reply();
        assertNotNull(reply);
        assertEquals(90L, reply.messageId());
        assertEquals(6L, reply.authorId());
        assertEquals("bob", reply.authorUsername());
        assertEquals("the original second line", reply.preview());
        assertEquals(java.time.Instant.parse("2026-01-01T10:00:00Z"), reply.createdAt());
        assertFalse(reply.deleted());
        // The reply ping: a USER mention of the original author with no span (offsets -1), and no
        // @token in the text.
        assertEquals(1, response.mentions().size());
        MentionResponse ping = response.mentions().get(0);
        assertEquals("USER", ping.type());
        assertEquals(6L, ping.id());
        assertEquals(-1, ping.start());
        assertEquals(-1, ping.end());
        verify(messagingTemplate).convertAndSend(
                eq(MessageService.CHANNEL_TOPIC_PREFIX + channelId), any(MessageResponse.class));
    }

    @Test
    void createReplyTargetInAnotherChannelIsNotFound() {
        Long channelId = 7L;
        Channel channel = new Channel("general", ChannelType.TEXT);
        channel.setId(channelId);
        User author = new User("alice", "hash");
        author.setId(5L);

        when(channelRepository.findById(channelId)).thenReturn(Optional.of(channel));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(author));
        when(messageRepository.findByIdAndChannelId(90L, channelId)).thenReturn(Optional.empty());

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class, () ->
                messageService.create(channelId, new CreateMessageRequest("a reply", null, null, 90L), "alice"));
        assertEquals(HttpStatus.NOT_FOUND, thrown.getStatusCode());
        verify(messagingTemplate, org.mockito.Mockito.never()).convertAndSend(any(String.class), any(Object.class));
    }

    @Test
    void createReplyToOwnMessageAddsNoSelfMention() {
        Long channelId = 7L;
        Channel channel = new Channel("general", ChannelType.TEXT);
        channel.setId(channelId);
        User author = new User("alice", "hash");
        author.setId(5L);
        Message original = new Message(channel, author, "my own");
        original.setId(90L);

        when(channelRepository.findById(channelId)).thenReturn(Optional.of(channel));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(author));
        when(messageRepository.findByIdAndChannelId(90L, channelId)).thenReturn(Optional.of(original));
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));
        when(mentionParser.parse(any(), any(), any())).thenReturn(java.util.List.of());

        MessageResponse response = messageService.create(channelId,
                new CreateMessageRequest("self reply", null, null, 90L), "alice");

        assertEquals(java.util.List.of(), response.mentions());
        assertNotNull(response.reply());
    }

    @Test
    void createReplyDoesNotDuplicateExplicitTextMention() {
        Long channelId = 7L;
        Channel channel = new Channel("general", ChannelType.TEXT);
        channel.setId(channelId);
        User author = new User("alice", "hash");
        author.setId(5L);
        User targetAuthor = new User("bob", "hash");
        targetAuthor.setId(6L);
        Message original = new Message(channel, targetAuthor, "the original");
        original.setId(90L);

        when(channelRepository.findById(channelId)).thenReturn(Optional.of(channel));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(author));
        when(messageRepository.findByIdAndChannelId(90L, channelId)).thenReturn(Optional.of(original));
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));
        // The content already carries "@bob" — the parser resolved it to a USER mention with a span.
        when(mentionParser.parse(any(), any(), any()))
                .thenReturn(java.util.List.of(new MentionResponse("USER", 6L, "bob", 0, 4)));

        MessageResponse response = messageService.create(channelId,
                new CreateMessageRequest("@bob look", null, null, 90L), "alice");

        assertEquals(1, response.mentions().size());
        assertEquals(0, response.mentions().get(0).start());
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
                new CreateMessageRequest(null, "attachments/abc/pic.png", "pic.png", null), "alice");

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

    @Test
    void forwardIntraInstanceCopiesPayloadAndCanonicalOrigin() {
        Long targetId = 7L;
        Long sourceId = 90L;
        Channel target = new Channel("target", ChannelType.TEXT);
        target.setId(targetId);
        Channel sourceChannel = new Channel("src", ChannelType.TEXT);
        sourceChannel.setId(8L);
        User actor = new User("alice", "hash");
        actor.setId(5L);
        User sourceAuthor = new User("bob", "hash");
        sourceAuthor.setId(6L);
        Message source = new Message(sourceChannel, sourceAuthor, "original text",
                "attachments/abc/pic.png", "pic.png");
        source.setId(sourceId);
        source.setCreatedAt(java.time.Instant.parse("2026-01-01T10:00:00Z"));
        ServerProfile profile = org.mockito.Mockito.mock(ServerProfile.class);
        when(profile.getName()).thenReturn("OAK Dev");
        java.util.concurrent.atomic.AtomicReference<Message> savedRef = new java.util.concurrent.atomic.AtomicReference<>();

        when(channelRepository.findById(targetId)).thenReturn(Optional.of(target));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(actor));
        when(messageRepository.findById(sourceId)).thenReturn(Optional.of(source));
        when(serverProfileRepository.singleton()).thenReturn(profile);
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> {
            savedRef.set(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(mentionParser.parse(any(), any(), any())).thenReturn(java.util.List.of());

        MessageResponse response = messageService.forward(targetId,
                new ForwardMessageRequest(sourceId, "client content ignored", null, null,
                        "Fake Server", "fake-channel", 99L, "faker", null),
                "alice");

        // Payload copied from the source, not from the client-supplied fields.
        assertEquals("original text", response.content());
        assertEquals("pic.png", response.attachmentFileName());
        // Canonical origin: profile name + source channel + source author + source time.
        assertEquals("OAK Dev", response.forward().serverName());
        assertEquals("src", response.forward().channelName());
        assertEquals(6L, response.forward().authorId());
        assertEquals("bob", response.forward().authorUsername());
        assertEquals(source.getCreatedAt(), response.forward().createdAt());
        assertTrue(response.forward().trusted());
        // A forward is never a reply.
        assertNull(response.reply());
        assertNull(savedRef.get().getRepliedToId());
        assertTrue(savedRef.get().getForwardTrusted());
        // Rides the BASE topic (the full MessageResponse needs no suffix of its own).
        verify(messagingTemplate).convertAndSend(
                eq(MessageService.CHANNEL_TOPIC_PREFIX + targetId), any(MessageResponse.class));
    }

    @Test
    void forwardSnapshotModeTakesClientOriginAndIsUntrusted() {
        Long targetId = 7L;
        Channel target = new Channel("target", ChannelType.TEXT);
        target.setId(targetId);
        User actor = new User("alice", "hash");
        actor.setId(5L);

        when(channelRepository.findById(targetId)).thenReturn(Optional.of(target));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(actor));
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));
        when(mentionParser.parse(any(), any(), any())).thenReturn(java.util.List.of());

        MessageResponse response = messageService.forward(targetId,
                new ForwardMessageRequest(null, "cross-instance text", null, null,
                        "Other Server", "their-channel", 42L, "carol",
                        java.time.Instant.parse("2026-02-02T12:00:00Z")),
                "alice");

        assertEquals("cross-instance text", response.content());
        assertEquals("Other Server", response.forward().serverName());
        assertEquals("their-channel", response.forward().channelName());
        assertEquals(42L, response.forward().authorId());
        assertEquals("carol", response.forward().authorUsername());
        assertFalse(response.forward().trusted());
    }

    @Test
    void forwardRequiresSendMessagesOnTargetChannel() {
        Long targetId = 7L;
        Channel target = new Channel("target", ChannelType.TEXT);
        target.setId(targetId);
        User actor = new User("alice", "hash");
        actor.setId(5L);

        when(channelRepository.findById(targetId)).thenReturn(Optional.of(target));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(actor));
        org.mockito.Mockito.doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "denied"))
                .when(auth).requireInChannel(actor, target, Permission.SEND_MESSAGES);

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class, () ->
                messageService.forward(targetId,
                        new ForwardMessageRequest(90L, "text", null, null, null, null, null, null, null),
                        "alice"));
        assertEquals(HttpStatus.FORBIDDEN, thrown.getStatusCode());
        verify(messageRepository, org.mockito.Mockito.never()).save(any(Message.class));
        verify(messagingTemplate, org.mockito.Mockito.never()).convertAndSend(any(String.class), any(Object.class));
    }
}
