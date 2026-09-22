package oak.server.service;

import oak.server.dto.AttachmentRef;
import oak.server.dto.AttachmentResponse;
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
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import org.mockito.ArgumentCaptor;

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
    @Mock
    private NestedReplyCodec nestedReplyCodec;
    @Mock
    private MessageIdempotencyCache idempotencyCache;
    @Mock
    private AttachmentObjectStore objectStore;
    /** Carries the "post landed in a discussion" hook to DiscussionService as an EVENT
     *  (decoupled — DiscussionService depends on this service for the lazy-create flow).
     *  Unused by every non-DISCUSSION test — legal under STRICT_STUBS. */
    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    /** A REAL codec, not a mock: every create/forward/toResponse call reads/writes the
     *  attachments JSON column, so a Mockito mock would force write/read stubs onto every test
     *  in this class. The spy wrapper only lets @InjectMocks pick it up as a dependency. */
    @Spy
    private AttachmentListCodec attachmentListCodec = new AttachmentListCodec(
            tools.jackson.databind.json.JsonMapper.builder().build());

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
        assertTrue(response.attachments().isEmpty());
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
    void createIntoADiscussionChannelFiresTheActivityHook() {
        Long channelId = 7L;
        Channel thread = new Channel("discussion-1", ChannelType.DISCUSSION);
        thread.setId(channelId);
        User author = new User("alice", "hash");
        Message saved = new Message(thread, author, "thread reply");
        saved.setId(101L);
        when(channelRepository.findById(channelId)).thenReturn(Optional.of(thread));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(author));
        when(messageRepository.save(any(Message.class))).thenReturn(saved);
        when(mentionParser.parse(any(), any(), any())).thenReturn(java.util.List.of());

        messageService.create(channelId, new CreateMessageRequest("thread reply", null, null), "alice");

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        MessagePostedInDiscussion event = (MessagePostedInDiscussion) eventCaptor.getValue();
        assertEquals(thread, event.channel());
        assertEquals(saved, event.message());
    }

    @Test
    void createServesIdempotentReplayWithoutSecondRowOrBroadcast() {
        // A retry whose first response was lost re-presents the same key: the ORIGINAL response
        // comes back, with no save and no second /topic push.
        MessageResponse original = new MessageResponse(55L, 7L, 5L, "alice", "hello", null,
                java.util.List.of(), java.util.List.of(), null, java.util.List.of(), null, null, false, null, null);
        when(idempotencyCache.get("alice", "key-1")).thenReturn(original);

        MessageResponse response = messageService.create(7L, new CreateMessageRequest("hello", null, null, "key-1"), "alice");

        assertEquals(55L, response.id());
        verify(messageRepository, org.mockito.Mockito.never()).save(any(Message.class));
        verify(messagingTemplate, org.mockito.Mockito.never()).convertAndSend(any(String.class), any(Object.class));
    }

    // -------------------------------------------------------------------------
    // Pins: field stamping, the .edited push, the cap, idempotency, the gate.
    // -------------------------------------------------------------------------

    /** A pin stub message whose save() echo returns the same row (the entity mutates in place). */
    private Message pinStubMessage(Channel channel, User author) {
        Message message = new Message(channel, author, "pin me");
        message.setId(100L);
        when(messageRepository.save(any(Message.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        return message;
    }

    @Test
    void pinStampsFieldsPushesEditedTopicAndReturnsTheRow() {
        Long channelId = 7L;
        Channel channel = new Channel("general", ChannelType.TEXT);
        channel.setId(channelId);
        User actor = new User("alice", "hash");
        actor.setId(5L);
        Message message = pinStubMessage(channel, actor);
        when(channelRepository.findById(channelId)).thenReturn(Optional.of(channel));
        when(messageRepository.findByIdAndChannelId(100L, channelId)).thenReturn(Optional.of(message));
        when(auth.requireUser("alice")).thenReturn(actor);
        when(messageRepository.countByChannelIdAndPinnedTrue(channelId)).thenReturn(0L);
        when(reactionRepository.findByMessageId(100L)).thenReturn(java.util.List.of());
        when(mentionParser.parse(any(), any(), any())).thenReturn(java.util.List.of());

        MessageResponse response = messageService.pin(channelId, 100L, "alice");

        assertTrue(response.pinned());
        assertEquals("alice", response.pinnedBy());
        assertNotNull(response.pinnedAt());
        verify(messagingTemplate).convertAndSend(
                eq(MessageService.CHANNEL_TOPIC_PREFIX + channelId + MessageService.EDITED_TOPIC_SUFFIX),
                any(MessageResponse.class));
    }

    @Test
    void pinRejectsThe51stDistinctPinWith400() {
        Long channelId = 7L;
        Channel channel = new Channel("general", ChannelType.TEXT);
        channel.setId(channelId);
        User actor = new User("alice", "hash");
        actor.setId(5L);
        Message message = new Message(channel, actor, "one too many");
        message.setId(100L);
        when(channelRepository.findById(channelId)).thenReturn(Optional.of(channel));
        when(messageRepository.findByIdAndChannelId(100L, channelId)).thenReturn(Optional.of(message));
        when(auth.requireUser("alice")).thenReturn(actor);
        when(messageRepository.countByChannelIdAndPinnedTrue(channelId)).thenReturn(50L);

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> messageService.pin(channelId, 100L, "alice"));
        assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatusCode());
        verify(messageRepository, org.mockito.Mockito.never()).save(any(Message.class));
    }

    @Test
    void rePinningKeepsTheOriginalPinnerAndMomentWithoutASave() {
        Long channelId = 7L;
        Channel channel = new Channel("general", ChannelType.TEXT);
        channel.setId(channelId);
        User bob = new User("bob", "hash");
        bob.setId(5L);
        User alice = new User("alice", "hash");
        alice.setId(6L);
        Message message = new Message(channel, bob, "already pinned");
        message.setId(100L);
        message.setPinned(true);
        message.setPinnedBy(5L);
        message.setPinnedByName("bob");
        message.setPinnedAt(java.time.Instant.parse("2026-01-01T00:00:00Z"));
        when(channelRepository.findById(channelId)).thenReturn(Optional.of(channel));
        when(messageRepository.findByIdAndChannelId(100L, channelId)).thenReturn(Optional.of(message));
        when(auth.requireUser("alice")).thenReturn(alice);
        when(reactionRepository.findByMessageId(100L)).thenReturn(java.util.List.of());
        when(mentionParser.parse(any(), any(), any())).thenReturn(java.util.List.of());

        MessageResponse response = messageService.pin(channelId, 100L, "alice");

        // attribution is NOT stolen by the re-pinner
        assertEquals("bob", response.pinnedBy());
        assertEquals(java.time.Instant.parse("2026-01-01T00:00:00Z"), response.pinnedAt());
        verify(messageRepository, org.mockito.Mockito.never()).save(any(Message.class));
    }

    @Test
    void unpinClearsAllFourFieldsTogetherAndPushes() {
        Long channelId = 7L;
        Channel channel = new Channel("general", ChannelType.TEXT);
        channel.setId(channelId);
        User actor = new User("alice", "hash");
        actor.setId(5L);
        Message message = pinStubMessage(channel, actor);
        message.setPinned(true);
        message.setPinnedBy(5L);
        message.setPinnedByName("alice");
        message.setPinnedAt(java.time.Instant.now());
        when(channelRepository.findById(channelId)).thenReturn(Optional.of(channel));
        when(messageRepository.findByIdAndChannelId(100L, channelId)).thenReturn(Optional.of(message));
        when(auth.requireUser("alice")).thenReturn(actor);
        when(reactionRepository.findByMessageId(100L)).thenReturn(java.util.List.of());
        when(mentionParser.parse(any(), any(), any())).thenReturn(java.util.List.of());

        MessageResponse response = messageService.unpin(channelId, 100L, "alice");

        assertFalse(response.pinned());
        assertNull(response.pinnedBy());
        assertNull(response.pinnedAt());
        verify(messagingTemplate).convertAndSend(
                eq(MessageService.CHANNEL_TOPIC_PREFIX + channelId + MessageService.EDITED_TOPIC_SUFFIX),
                any(MessageResponse.class));
    }

    @Test
    void pinGatesOnManageMessages() {
        Long channelId = 7L;
        Channel channel = new Channel("general", ChannelType.TEXT);
        channel.setId(channelId);
        User actor = new User("bob", "hash");
        actor.setId(5L);
        Message message = new Message(channel, actor, "locked");
        message.setId(100L);
        when(channelRepository.findById(channelId)).thenReturn(Optional.of(channel));
        when(messageRepository.findByIdAndChannelId(100L, channelId)).thenReturn(Optional.of(message));
        when(auth.requireUser("bob")).thenReturn(actor);
        org.mockito.Mockito.doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "no pins for you"))
                .when(auth).requireInChannel(actor, channel, Permission.MANAGE_MESSAGES);

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> messageService.pin(channelId, 100L, "bob"));
        assertEquals(HttpStatus.FORBIDDEN, thrown.getStatusCode());
    }

    // -------------------------------------------------------------------------
    // The event tier of storage GC: an object dies the moment its LAST reference
    // dies — a key still referenced by any remaining message (a forward) stays.
    // -------------------------------------------------------------------------

    @Test
    void deleteRemovesAttachmentObjectsNoLongerReferencedAnywhere() {
        Long channelId = 7L;
        Channel channel = new Channel("general", ChannelType.TEXT);
        channel.setId(channelId);
        User author = new User("alice", "hash");
        author.setId(5L);
        Message existing = new Message(channel, author, "pics");
        existing.setId(100L);
        existing.setAttachments(attachmentListCodec.write(java.util.List.of(
                new AttachmentRef("att/orphan", "a.png", null, null),
                new AttachmentRef("att/forwarded", "b.png", null, null))));

        when(channelRepository.findById(channelId)).thenReturn(Optional.of(channel));
        when(messageRepository.findByIdAndChannelId(100L, channelId)).thenReturn(Optional.of(existing));
        when(auth.requireUser("alice")).thenReturn(author);
        // "att/forwarded" still hangs on another message (a forward's copy) — it must survive.
        when(messageRepository.countByAttachmentsTextLike("%att/orphan%")).thenReturn(0L);
        when(messageRepository.countByAttachmentsTextLike("%att/forwarded%")).thenReturn(1L);

        messageService.delete(channelId, 100L, "alice");

        verify(objectStore).delete("att/orphan");
        verify(objectStore, org.mockito.Mockito.never()).delete("att/forwarded");
    }

    @Test
    void editDroppingAttachmentsDeletesOnlyTheRemovedOrphans() {
        Long channelId = 7L;
        Long messageId = 100L;
        Channel channel = new Channel("general", ChannelType.TEXT);
        channel.setId(channelId);
        User author = new User("alice", "hash");
        author.setId(5L);
        Message existing = new Message(channel, author, "pics");
        existing.setId(messageId);
        existing.setAttachments(attachmentListCodec.write(java.util.List.of(
                new AttachmentRef("att/keep", "keep.png", null, null),
                new AttachmentRef("att/drop", "drop.png", null, null))));

        when(channelRepository.findById(channelId)).thenReturn(Optional.of(channel));
        when(messageRepository.findByIdAndChannelId(messageId, channelId)).thenReturn(Optional.of(existing));
        when(auth.requireUser("alice")).thenReturn(author);
        when(messageRepository.save(any(Message.class))).thenReturn(existing);
        when(mentionParser.parse(any(), any(), any())).thenReturn(java.util.List.of());
        when(reactionRepository.findByMessageId(messageId)).thenReturn(java.util.List.of());
        when(messageRepository.countByAttachmentsTextLike("%att/drop%")).thenReturn(0L);

        messageService.edit(channelId, messageId, new EditMessageRequest("pics",
                java.util.List.of(new AttachmentRef("att/keep", "keep.png", null, null))), "alice");

        verify(objectStore).delete("att/drop");
        verify(objectStore, org.mockito.Mockito.never()).delete("att/keep");
    }

    @Test
    void createStoresTheResponseUnderTheKeyForFutureReplays() {
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

        messageService.create(channelId, new CreateMessageRequest("hello", null, null, "key-1"), "alice");

        verify(idempotencyCache).put(eq("alice"), eq("key-1"), any(MessageResponse.class));
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
        when(messageRepository.findAllById(any())).thenReturn(java.util.List.of(original));
        when(mentionParser.parse(any(), any(), any())).thenReturn(java.util.List.of());

        MessageResponse response = messageService.create(channelId,
                new CreateMessageRequest("a reply", null, 90L), "alice");

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
                messageService.create(channelId, new CreateMessageRequest("a reply", null, 90L), "alice"));
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
        when(messageRepository.findAllById(any())).thenReturn(java.util.List.of(original));
        when(mentionParser.parse(any(), any(), any())).thenReturn(java.util.List.of());

        MessageResponse response = messageService.create(channelId,
                new CreateMessageRequest("self reply", null, 90L), "alice");

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
        when(messageRepository.findAllById(any())).thenReturn(java.util.List.of(original));
        // The content already carries "@bob" — the parser resolved it to a USER mention with a span.
        when(mentionParser.parse(any(), any(), any()))
                .thenReturn(java.util.List.of(new MentionResponse("USER", 6L, "bob", 0, 4)));

        MessageResponse response = messageService.create(channelId,
                new CreateMessageRequest("@bob look", null, 90L), "alice");

        assertEquals(1, response.mentions().size());
        assertEquals(0, response.mentions().get(0).start());
    }

    @Test
    void createAcceptsAttachmentOnlyMessageAndSignsGetUrl() {
        Long channelId = 7L;
        Channel channel = new Channel("general", ChannelType.TEXT);
        channel.setId(channelId);
        User author = new User("alice", "hash");
        AttachmentRef pic = new AttachmentRef("attachments/abc/pic.png", "pic.png");
        Message saved = new Message(channel, author, null);
        saved.setId(101L);
        saved.setAttachments(attachmentListCodec.write(java.util.List.of(pic)));

        when(channelRepository.findById(channelId)).thenReturn(Optional.of(channel));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(author));
        when(attachmentUrlSigner.presignedGetUrl("attachments/abc/pic.png")).thenReturn("https://minio/get/pic.png");
        when(messageRepository.save(any(Message.class))).thenReturn(saved);
        when(mentionParser.parse(any(), any(), any())).thenReturn(java.util.List.of());

        MessageResponse response = messageService.create(channelId,
                new CreateMessageRequest(null, java.util.List.of(pic), null), "alice");

        assertNull(response.content());
        assertEquals(1, response.attachments().size());
        assertEquals("pic.png", response.attachments().get(0).fileName());
        assertEquals("https://minio/get/pic.png", response.attachments().get(0).url());
        verify(attachmentUrlSigner).presignedGetUrl("attachments/abc/pic.png");
        verify(messagingTemplate).convertAndSend(
                eq(MessageService.CHANNEL_TOPIC_PREFIX + channelId), any(MessageResponse.class));
    }

    @Test
    void createSignsTheVideoPreviewAlongsideTheVideo() {
        Long channelId = 7L;
        Channel channel = new Channel("general", ChannelType.TEXT);
        channel.setId(channelId);
        User author = new User("alice", "hash");
        AttachmentRef clip = new AttachmentRef("attachments/abc/clip.mp4", "clip.mp4",
                "attachments/def/clip-thumb.jpg", 65_000L);
        Message saved = new Message(channel, author, null);
        saved.setId(103L);
        saved.setAttachments(attachmentListCodec.write(java.util.List.of(clip)));

        when(channelRepository.findById(channelId)).thenReturn(Optional.of(channel));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(author));
        when(attachmentUrlSigner.presignedGetUrl("attachments/abc/clip.mp4"))
                .thenReturn("https://minio/get/clip.mp4");
        when(attachmentUrlSigner.presignedGetUrl("attachments/def/clip-thumb.jpg"))
                .thenReturn("https://minio/get/clip-thumb.jpg");
        when(messageRepository.save(any(Message.class))).thenReturn(saved);
        when(mentionParser.parse(any(), any(), any())).thenReturn(java.util.List.of());

        MessageResponse response = messageService.create(channelId,
                new CreateMessageRequest(null, java.util.List.of(clip), null), "alice");

        AttachmentResponse attachment = response.attachments().get(0);
        assertEquals("attachments/def/clip-thumb.jpg", attachment.thumbnailKey());
        assertEquals("https://minio/get/clip-thumb.jpg", attachment.thumbnailUrl());
        assertEquals(65_000L, attachment.durationMillis());
        verify(attachmentUrlSigner).presignedGetUrl("attachments/def/clip-thumb.jpg");
        // The preview metadata persists into the JSON column, not just the response.
        java.util.List<AttachmentRef> stored =
                attachmentListCodec.read(saved.getAttachments());
        assertEquals("attachments/def/clip-thumb.jpg", stored.get(0).thumbnailKey());
    }

    @Test
    void createSignsEveryAttachmentOfAMultiAttachmentMessage() {
        Long channelId = 7L;
        Channel channel = new Channel("general", ChannelType.TEXT);
        channel.setId(channelId);
        User author = new User("alice", "hash");
        AttachmentRef pic = new AttachmentRef("attachments/abc/pic.png", "pic.png");
        AttachmentRef doc = new AttachmentRef("attachments/abc/doc.pdf", "doc.pdf");
        Message saved = new Message(channel, author, "two files");
        saved.setId(102L);
        saved.setAttachments(attachmentListCodec.write(java.util.List.of(pic, doc)));

        when(channelRepository.findById(channelId)).thenReturn(Optional.of(channel));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(author));
        when(attachmentUrlSigner.presignedGetUrl("attachments/abc/pic.png")).thenReturn("https://minio/get/pic.png");
        when(attachmentUrlSigner.presignedGetUrl("attachments/abc/doc.pdf")).thenReturn("https://minio/get/doc.pdf");
        when(messageRepository.save(any(Message.class))).thenReturn(saved);
        when(mentionParser.parse(any(), any(), any())).thenReturn(java.util.List.of());

        MessageResponse response = messageService.create(channelId,
                new CreateMessageRequest("two files", java.util.List.of(pic, doc), null), "alice");

        java.util.List<AttachmentResponse> attachments = response.attachments();
        assertEquals(2, attachments.size());
        assertEquals("pic.png", attachments.get(0).fileName());
        assertEquals("https://minio/get/pic.png", attachments.get(0).url());
        assertEquals("doc.pdf", attachments.get(1).fileName());
        assertEquals("https://minio/get/doc.pdf", attachments.get(1).url());
        verify(attachmentUrlSigner).presignedGetUrl("attachments/abc/pic.png");
        verify(attachmentUrlSigner).presignedGetUrl("attachments/abc/doc.pdf");
    }

    @Test
    void createRejectsMoreThanTenAttachments() {
        java.util.List<AttachmentRef> eleven = new java.util.ArrayList<>();
        for (int attachmentIndex = 0; attachmentIndex < 11; attachmentIndex++) {
            eleven.add(new AttachmentRef("attachments/abc/file-" + attachmentIndex, "file-" + attachmentIndex));
        }
        ResponseStatusException thrown = assertThrows(ResponseStatusException.class, () ->
                messageService.create(7L, new CreateMessageRequest("too many", eleven, null), "alice"));
        assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatusCode());
        verify(messageRepository, org.mockito.Mockito.never()).save(any(Message.class));
    }

    @Test
    void createRejectsAttachmentWithBlankKey() {
        ResponseStatusException thrown = assertThrows(ResponseStatusException.class, () ->
                messageService.create(7L,
                        new CreateMessageRequest("no key", java.util.List.of(new AttachmentRef("  ", "pic.png")), null),
                        "alice"));
        assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatusCode());
        verify(messageRepository, org.mockito.Mockito.never()).save(any(Message.class));
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
                channelId, messageId, new EditMessageRequest("hello (fixed)", null), "alice");

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
    void editReplacesAttachmentsWithRequestedSubset() {
        Long channelId = 7L;
        Long messageId = 100L;
        Channel channel = new Channel("general", ChannelType.TEXT);
        channel.setId(channelId);
        User author = new User("alice", "hash");
        author.setId(5L);
        AttachmentRef pic = new AttachmentRef("attachments/abc/pic.png", "pic.png");
        AttachmentRef doc = new AttachmentRef("attachments/abc/doc.pdf", "doc.pdf");
        Message existing = new Message(channel, author, "two files");
        existing.setId(messageId);
        existing.setAttachments(attachmentListCodec.write(java.util.List.of(pic, doc)));

        when(channelRepository.findById(channelId)).thenReturn(Optional.of(channel));
        when(messageRepository.findByIdAndChannelId(messageId, channelId)).thenReturn(Optional.of(existing));
        when(auth.requireUser("alice")).thenReturn(author);
        when(messageRepository.save(any(Message.class))).thenReturn(existing);
        when(mentionParser.parse(any(), any(), any())).thenReturn(java.util.List.of());
        when(reactionRepository.findByMessageId(messageId)).thenReturn(java.util.List.of());

        MessageResponse response = messageService.edit(channelId, messageId,
                new EditMessageRequest("one file now", java.util.List.of(doc)), "alice");

        // The column now holds exactly the requested subset; the response carries the key.
        java.util.List<AttachmentRef> stored = attachmentListCodec.read(existing.getAttachments());
        assertEquals(1, stored.size());
        assertEquals("attachments/abc/doc.pdf", stored.get(0).key());
        assertEquals(1, response.attachments().size());
        assertEquals("doc.pdf", response.attachments().get(0).fileName());
        assertEquals("attachments/abc/doc.pdf", response.attachments().get(0).key());
        verify(messagingTemplate).convertAndSend(
                eq(MessageService.CHANNEL_TOPIC_PREFIX + channelId + MessageService.EDITED_TOPIC_SUFFIX),
                any(MessageResponse.class));
    }

    @Test
    void editRejectsForeignAttachmentKey() {
        Long channelId = 7L;
        Long messageId = 100L;
        Channel channel = new Channel("general", ChannelType.TEXT);
        channel.setId(channelId);
        User author = new User("alice", "hash");
        author.setId(5L);
        AttachmentRef pic = new AttachmentRef("attachments/abc/pic.png", "pic.png");
        Message existing = new Message(channel, author, "one file");
        existing.setId(messageId);
        existing.setAttachments(attachmentListCodec.write(java.util.List.of(pic)));

        when(channelRepository.findById(channelId)).thenReturn(Optional.of(channel));
        when(messageRepository.findByIdAndChannelId(messageId, channelId)).thenReturn(Optional.of(existing));
        when(auth.requireUser("alice")).thenReturn(author);

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class, () ->
                messageService.edit(channelId, messageId,
                        new EditMessageRequest("edit",
                                java.util.List.of(new AttachmentRef("attachments/zzz/foreign.png", "foreign.png"))),
                        "alice"));
        assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatusCode());
        // Validation runs BEFORE any mutation — the row and the push are untouched.
        verify(messageRepository, org.mockito.Mockito.never()).save(any(Message.class));
        verify(messagingTemplate, org.mockito.Mockito.never()).convertAndSend(any(String.class), any(Object.class));
    }

    @Test
    void editRejectsBlankContentWhenNoAttachmentsRemain() {
        Long channelId = 7L;
        Long messageId = 100L;
        Channel channel = new Channel("general", ChannelType.TEXT);
        channel.setId(channelId);
        User author = new User("alice", "hash");
        author.setId(5L);
        AttachmentRef pic = new AttachmentRef("attachments/abc/pic.png", "pic.png");
        Message existing = new Message(channel, author, null);   // attachment-only
        existing.setId(messageId);
        existing.setAttachments(attachmentListCodec.write(java.util.List.of(pic)));

        when(channelRepository.findById(channelId)).thenReturn(Optional.of(channel));
        when(messageRepository.findByIdAndChannelId(messageId, channelId)).thenReturn(Optional.of(existing));
        when(auth.requireUser("alice")).thenReturn(author);

        // Stripping the last attachment while clearing the text would leave an empty message.
        ResponseStatusException thrown = assertThrows(ResponseStatusException.class, () ->
                messageService.edit(channelId, messageId, new EditMessageRequest("   ", java.util.List.of()), "alice"));
        assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatusCode());
        verify(messageRepository, org.mockito.Mockito.never()).save(any(Message.class));
    }

    @Test
    void editNullAttachmentsListLeavesAttachmentsUntouched() {
        Long channelId = 7L;
        Long messageId = 100L;
        Channel channel = new Channel("general", ChannelType.TEXT);
        channel.setId(channelId);
        User author = new User("alice", "hash");
        author.setId(5L);
        AttachmentRef pic = new AttachmentRef("attachments/abc/pic.png", "pic.png");
        Message existing = new Message(channel, author, "keep files");
        existing.setId(messageId);
        existing.setAttachments(attachmentListCodec.write(java.util.List.of(pic)));

        when(channelRepository.findById(channelId)).thenReturn(Optional.of(channel));
        when(messageRepository.findByIdAndChannelId(messageId, channelId)).thenReturn(Optional.of(existing));
        when(auth.requireUser("alice")).thenReturn(author);
        when(messageRepository.save(any(Message.class))).thenReturn(existing);
        when(mentionParser.parse(any(), any(), any())).thenReturn(java.util.List.of());
        when(reactionRepository.findByMessageId(messageId)).thenReturn(java.util.List.of());

        MessageResponse response = messageService.edit(channelId, messageId,
                new EditMessageRequest("text only edit", null), "alice");

        assertEquals(1, response.attachments().size());
        assertEquals(1, attachmentListCodec.read(existing.getAttachments()).size());
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
                messageService.edit(channelId, messageId, new EditMessageRequest("bob was here", null), "bob"));
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

    /** The shared fixture of the intra-instance forward tests: a TWO-attachment source message
     *  ("original text" by bob in channel "src" of this instance) plus the target/actor stubs.
     *  The stubs only the HAPPY paths need are lenient — the 400-cases below never reach them
     *  (strict stubs would flag them unused). */
    private record ForwardFixture(Long targetId, Long sourceId, AttachmentRef pic, AttachmentRef doc,
                                  Message source,
                                  java.util.concurrent.atomic.AtomicReference<Message> savedRef) {
    }

    private ForwardFixture seedForwardSource() {
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
        AttachmentRef pic = new AttachmentRef("attachments/abc/pic.png", "pic.png");
        AttachmentRef doc = new AttachmentRef("attachments/abc/doc.pdf", "doc.pdf");
        Message source = new Message(sourceChannel, sourceAuthor, "original text");
        source.setId(sourceId);
        source.setAttachments(attachmentListCodec.write(java.util.List.of(pic, doc)));
        source.setCreatedAt(java.time.Instant.parse("2026-01-01T10:00:00Z"));
        ServerProfile profile = org.mockito.Mockito.mock(ServerProfile.class);
        org.mockito.Mockito.lenient().when(profile.getName()).thenReturn("OAK Dev");
        java.util.concurrent.atomic.AtomicReference<Message> savedRef = new java.util.concurrent.atomic.AtomicReference<>();

        when(channelRepository.findById(targetId)).thenReturn(Optional.of(target));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(actor));
        when(messageRepository.findById(sourceId)).thenReturn(Optional.of(source));
        org.mockito.Mockito.lenient().when(serverProfileRepository.singleton()).thenReturn(profile);
        org.mockito.Mockito.lenient().when(attachmentUrlSigner.presignedGetUrl("attachments/abc/pic.png"))
                .thenReturn("https://minio/get/pic.png");
        org.mockito.Mockito.lenient().when(attachmentUrlSigner.presignedGetUrl("attachments/abc/doc.pdf"))
                .thenReturn("https://minio/get/doc.pdf");
        org.mockito.Mockito.lenient().when(messageRepository.save(any(Message.class))).thenAnswer(inv -> {
            savedRef.set(inv.getArgument(0));
            return inv.getArgument(0);
        });
        org.mockito.Mockito.lenient().when(mentionParser.parse(any(), any(), any()))
                .thenReturn(java.util.List.of());
        return new ForwardFixture(targetId, sourceId, pic, doc, source, savedRef);
    }

    @Test
    void forwardIntraInstanceSubsetCopiesOnlyChosenAttachment() {
        ForwardFixture fixture = seedForwardSource();

        MessageResponse response = messageService.forward(fixture.targetId(),
                new ForwardMessageRequest(fixture.sourceId(), null,
                        java.util.List.of(fixture.pic()), null, null, null, null, null, null),
                "alice");

        // The image viewer's "forward the image": ONLY the chosen file, and the source's text is
        // dropped (it was written about all the images).
        assertNull(response.content());
        assertEquals(1, response.attachments().size());
        assertEquals("pic.png", response.attachments().get(0).fileName());
        assertEquals("https://minio/get/pic.png", response.attachments().get(0).url());
        verify(attachmentUrlSigner, org.mockito.Mockito.never()).presignedGetUrl("attachments/abc/doc.pdf");
        // Canonical TRUSTED origin still derived server-side.
        assertEquals("OAK Dev", response.forward().serverName());
        assertEquals("src", response.forward().channelName());
        assertEquals(6L, response.forward().authorId());
        assertEquals("bob", response.forward().authorUsername());
        assertEquals(fixture.source().getCreatedAt(), response.forward().createdAt());
        assertTrue(response.forward().trusted());
        // The saved row mirrors the response.
        assertNull(fixture.savedRef().get().getContent());
        assertEquals(1, attachmentListCodec.read(fixture.savedRef().get().getAttachments()).size());
        assertTrue(fixture.savedRef().get().getForwardTrusted());
        assertNull(fixture.savedRef().get().getRepliedToId());
        verify(messagingTemplate).convertAndSend(
                eq(MessageService.CHANNEL_TOPIC_PREFIX + fixture.targetId()), any(MessageResponse.class));
    }

    @Test
    void forwardIntraInstanceSubsetRejectsForeignKey() {
        ForwardFixture fixture = seedForwardSource();

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class, () ->
                messageService.forward(fixture.targetId(),
                        new ForwardMessageRequest(fixture.sourceId(), null,
                                java.util.List.of(new AttachmentRef("client/fake.png", "fake.png")),
                                null, null, null, null, null, null),
                        "alice"));
        assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatusCode());
        verify(messageRepository, org.mockito.Mockito.never()).save(any(Message.class));
        verify(messagingTemplate, org.mockito.Mockito.never()).convertAndSend(any(String.class), any(Object.class));
    }

    @Test
    void forwardIntraInstanceSubsetRejectsDuplicate() {
        ForwardFixture fixture = seedForwardSource();

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class, () ->
                messageService.forward(fixture.targetId(),
                        new ForwardMessageRequest(fixture.sourceId(), null,
                                java.util.List.of(fixture.pic(), fixture.pic()),
                                null, null, null, null, null, null),
                        "alice"));
        assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatusCode());
        verify(messageRepository, org.mockito.Mockito.never()).save(any(Message.class));
    }

    @Test
    void forwardIntraInstanceSubsetRejectsEmptyList() {
        ForwardFixture fixture = seedForwardSource();

        // An empty subset would drop the content AND the attachments — fail with a clear 400
        // instead of the generic content-or-attachment one at the bottom of forward().
        ResponseStatusException thrown = assertThrows(ResponseStatusException.class, () ->
                messageService.forward(fixture.targetId(),
                        new ForwardMessageRequest(fixture.sourceId(), null, java.util.List.of(),
                                null, null, null, null, null, null),
                        "alice"));
        assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatusCode());
        verify(messageRepository, org.mockito.Mockito.never()).save(any(Message.class));
    }

    @Test
    void forwardIntraInstanceNullAttachmentListCopiesAll() {
        ForwardFixture fixture = seedForwardSource();

        MessageResponse response = messageService.forward(fixture.targetId(),
                new ForwardMessageRequest(fixture.sourceId(), "client content ignored",
                        null, "Fake Server", "fake-channel", 99L, "faker", null, null),
                "alice");

        // Legacy whole-message forward: null attachments = copy EVERYTHING, client-supplied
        // payload/origin fields ignored.
        assertEquals("original text", response.content());
        assertEquals(2, response.attachments().size());
        assertEquals("pic.png", response.attachments().get(0).fileName());
        assertEquals("doc.pdf", response.attachments().get(1).fileName());
        assertEquals("https://minio/get/pic.png", response.attachments().get(0).url());
        assertEquals("https://minio/get/doc.pdf", response.attachments().get(1).url());
        verify(attachmentUrlSigner).presignedGetUrl("attachments/abc/pic.png");
        verify(attachmentUrlSigner).presignedGetUrl("attachments/abc/doc.pdf");
        // Canonical origin: profile name + source channel + source author + source time.
        assertEquals("OAK Dev", response.forward().serverName());
        assertEquals("src", response.forward().channelName());
        assertEquals(6L, response.forward().authorId());
        assertEquals("bob", response.forward().authorUsername());
        assertEquals(fixture.source().getCreatedAt(), response.forward().createdAt());
        assertTrue(response.forward().trusted());
        // A forward is never a reply.
        assertNull(response.reply());
        assertNull(fixture.savedRef().get().getRepliedToId());
        assertTrue(fixture.savedRef().get().getForwardTrusted());
        // Rides the BASE topic (the full MessageResponse needs no suffix of its own).
        verify(messagingTemplate).convertAndSend(
                eq(MessageService.CHANNEL_TOPIC_PREFIX + fixture.targetId()), any(MessageResponse.class));
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
                new ForwardMessageRequest(null, "cross-instance text", null,
                        "Other Server", "their-channel", 42L, "carol",
                        java.time.Instant.parse("2026-02-02T12:00:00Z"), null),
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
