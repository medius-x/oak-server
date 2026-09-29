package oak.server.service;

import oak.server.dto.CreateDiscussionRequest;
import oak.server.dto.DiscussionEvent;
import oak.server.dto.DiscussionResponse;
import oak.server.dto.MessageResponse;
import oak.server.dto.MentionResponse;
import oak.server.entity.Channel;
import oak.server.entity.ChannelType;
import oak.server.entity.Discussion;
import oak.server.entity.Message;
import oak.server.entity.Permission;
import oak.server.entity.User;
import oak.server.repository.ChannelParticipantRepository;
import oak.server.repository.ChannelReadStateRepository;
import oak.server.repository.ChannelRepository;
import oak.server.repository.DiscussionRepository;
import oak.server.repository.MessageReactionRepository;
import oak.server.repository.MessageRepository;
import oak.server.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mockito tests for the discussion lifecycle: the create gates, the lazy create-with-first-
 * message flow (conflict reuses the thread, the message is never lost), the mention
 * auto-join, the CREATED/ACTIVITY/REMOVED broadcast destinations and payloads, and the
 * FK-safe close teardown chain.
 */
@ExtendWith(MockitoExtension.class)
class DiscussionServiceTest {

    @Mock
    private ChannelRepository channelRepository;
    @Mock
    private DiscussionRepository discussionRepository;
    @Mock
    private MessageRepository messageRepository;
    @Mock
    private MessageReactionRepository reactionRepository;
    @Mock
    private ChannelParticipantRepository participantRepository;
    @Mock
    private ChannelReadStateRepository readStateRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AuthorizationService auth;
    @Mock
    private SimpMessagingTemplate messagingTemplate;
    @Mock
    private AttachmentObjectStore objectStore;
    @Mock
    private AttachmentListCodec attachmentListCodec;
    @Mock
    private MentionParser mentionParser;
    @Mock
    private MessageService messageService;

    @InjectMocks
    private DiscussionService discussionService;

    private Channel textChannel(Long id) {
        Channel channel = new Channel("general", ChannelType.TEXT);
        channel.setId(id);
        return channel;
    }

    private Message originMessage(Long id, Channel channel, User author) {
        Message message = new Message(channel, author, "the origin text");
        message.setId(id);
        return message;
    }

    private CreateDiscussionRequest bareRequest(Long messageId) {
        return new CreateDiscussionRequest(messageId, null, null, null);
    }

    @Test
    void createSnapshotsTheOriginPublishesCreatedWithTheFullRowAndTracksTheCreator() {
        Channel parent = textChannel(7L);
        User actor = new User("alice", "hash");
        actor.setId(5L);
        User originAuthor = new User("carol", "hash");
        originAuthor.setId(9L);
        Message origin = originMessage(100L, parent, originAuthor);
        Channel thread = new Channel("discussion-100", ChannelType.DISCUSSION);
        thread.setId(77L);
        when(channelRepository.findById(7L)).thenReturn(Optional.of(parent));
        when(messageRepository.findByIdAndChannelId(100L, 7L)).thenReturn(Optional.of(origin));
        when(discussionRepository.findByOriginMessageId(100L)).thenReturn(Optional.empty());
        when(auth.requireUser("alice")).thenReturn(actor);
        when(channelRepository.save(any(Channel.class))).thenReturn(thread);
        when(discussionRepository.save(any(Discussion.class))).thenAnswer(invocation -> invocation.getArgument(0));
        // the creator's just-saved participant row — the tracked flag of the response reads it back
        when(participantRepository.existsByChannelIdAndUserId(77L, 5L)).thenReturn(true);
        when(participantRepository.existsByChannelIdAndUserId(77L, 9L)).thenReturn(false);
        when(messageRepository.countByChannelId(77L)).thenReturn(0L);

        DiscussionResponse response = discussionService.create(7L, bareRequest(100L), "alice");

        assertEquals(77L, response.id());
        assertEquals("the origin text", response.originSnippet());
        assertEquals("carol", response.originAuthorName());
        assertTrue(response.tracked());
        // BOTH the creator AND the origin author join ("a thread on someone else's message
        // auto-tracks them" — the first-reply mention ping needs them in the mine list)
        verify(participantRepository, org.mockito.Mockito.times(2))
                .save(any(oak.server.entity.ChannelParticipant.class));
        ArgumentCaptor<DiscussionEvent> event = ArgumentCaptor.forClass(DiscussionEvent.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/channel.7.discussions"), event.capture());
        assertEquals(DiscussionEvent.TYPE_CREATED, event.getValue().type());
        assertEquals("alice", event.getValue().actorUsername());
        // the broadcast carries the FULL row (tracked=false for everyone — viewer-scoped)
        assertEquals(77L, event.getValue().discussion().id());
        assertEquals(false, event.getValue().discussion().tracked());
        assertNull(event.getValue().message());
    }

    @Test
    void lazyCreateWithContentPostsTheFirstMessageThroughTheOrdinaryPath() {
        Channel parent = textChannel(7L);
        User actor = new User("alice", "hash");
        actor.setId(5L);
        Message origin = originMessage(100L, parent, actor);
        Channel thread = new Channel("discussion-100", ChannelType.DISCUSSION);
        thread.setId(77L);
        when(channelRepository.findById(7L)).thenReturn(Optional.of(parent));
        when(messageRepository.findByIdAndChannelId(100L, 7L)).thenReturn(Optional.of(origin));
        when(discussionRepository.findByOriginMessageId(100L)).thenReturn(Optional.empty());
        when(auth.requireUser("alice")).thenReturn(actor);
        when(channelRepository.save(any(Channel.class))).thenReturn(thread);
        when(discussionRepository.save(any(Discussion.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(participantRepository.existsByChannelIdAndUserId(77L, 5L)).thenReturn(true);
        when(messageRepository.countByChannelId(77L)).thenReturn(1L);

        discussionService.create(7L, new CreateDiscussionRequest(100L, "first reply", null, "key-1"), "alice");

        // the draft's first reply went through MessageService.create (gates, broadcast and
        // the activity event all ride the ordinary message path)
        verify(messageService).create(eq(77L), eq(new oak.server.dto.CreateMessageRequest(
                "first reply", null, null, "key-1")), eq("alice"));
    }

    @Test
    void lazyCreateOnConflictReusesTheExistingThreadInsteadOfLosingTheMessage() {
        Channel parent = textChannel(7L);
        User actor = new User("bob", "hash");
        actor.setId(6L);
        Message origin = originMessage(100L, parent, new User("alice", "hash"));
        Discussion existing = new Discussion(77L, 7L, 100L, "s", 5L, "alice", Instant.now());
        when(channelRepository.findById(7L)).thenReturn(Optional.of(parent));
        when(messageRepository.findByIdAndChannelId(100L, 7L)).thenReturn(Optional.of(origin));
        when(discussionRepository.findByOriginMessageId(100L)).thenReturn(Optional.of(existing));
        when(auth.requireUser("bob")).thenReturn(actor);
        when(participantRepository.existsByChannelIdAndUserId(77L, 6L)).thenReturn(true);
        when(messageRepository.countByChannelId(77L)).thenReturn(2L);

        // WITH a first message the conflict is a REUSE (200-path), not a 409 — the message
        // must never be lost to a race the draft cannot resolve
        discussionService.create(7L, new CreateDiscussionRequest(100L, "my reply", null, null), "bob");
        verify(messageService).create(eq(77L), any(oak.server.dto.CreateMessageRequest.class), eq("bob"));
        verify(channelRepository, never()).save(any(Channel.class));

        // the BARE create keeps the honest 409 (the API surface for direct callers)
        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> discussionService.create(7L, bareRequest(100L), "bob"));
        assertEquals(HttpStatus.CONFLICT, thrown.getStatusCode());
    }

    @Test
    void createRejectsNonTextParentsAndForeignOrigins() {
        User actor = new User("alice", "hash");
        Channel voice = new Channel("lounge", ChannelType.VOICE);
        voice.setId(9L);
        when(channelRepository.findById(9L)).thenReturn(Optional.of(voice));
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> discussionService.create(9L, bareRequest(1L), "alice")).getStatusCode());

        Channel parent = textChannel(7L);
        when(channelRepository.findById(7L)).thenReturn(Optional.of(parent));
        when(messageRepository.findByIdAndChannelId(100L, 7L)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> discussionService.create(7L, bareRequest(100L), "alice")).getStatusCode());

        User author = new User("alice", "hash");
        when(messageRepository.findByIdAndChannelId(100L, 7L))
                .thenReturn(Optional.of(originMessage(100L, parent, author)));
        when(discussionRepository.findByOriginMessageId(100L))
                .thenReturn(Optional.of(new Discussion(77L, 7L, 100L, "s", 5L, "alice", Instant.now())));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> discussionService.create(7L, bareRequest(100L), "alice")).getStatusCode());
    }

    @Test
    void activityAutoJoinsTheAuthorAndTheMentionedUsersAndCarriesTheMessage() {
        Channel thread = new Channel("discussion-1", ChannelType.DISCUSSION);
        thread.setId(77L);
        User author = new User("bob", "hash");
        author.setId(6L);
        User mentioned = new User("carol", "hash");
        mentioned.setId(9L);
        Message posted = new Message(thread, author, "hey @carol look");
        posted.setId(200L);
        posted.setCreatedAt(Instant.parse("2026-09-22T02:00:00Z"));
        Discussion discussion = new Discussion(77L, 7L, 100L, "s", 5L, "alice", Instant.now());
        when(discussionRepository.findByChannelId(77L)).thenReturn(Optional.of(discussion));
        when(participantRepository.existsByChannelIdAndUserId(77L, 6L)).thenReturn(false);
        when(participantRepository.existsByChannelIdAndUserId(77L, 9L)).thenReturn(false);
        when(mentionParser.parse(any(), any(), any())).thenReturn(List.of(
                new MentionResponse("USER", 9L, "carol", 4, 10)));
        when(userRepository.findAllById(List.of(9L))).thenReturn(List.of(mentioned));
        when(discussionRepository.save(any(Discussion.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.countByChannelId(77L)).thenReturn(4L);
        MessageResponse wireMessage = new MessageResponse(200L, 77L, 6L, "bob", "hey @carol look",
                Instant.parse("2026-09-22T02:00:00Z"), List.of(), List.of(), null, List.of(),
                null, null, false, null, null, null);
        when(messageService.responseOf(posted)).thenReturn(wireMessage);

        discussionService.onMessagePostedInDiscussion(new MessagePostedInDiscussion(thread, posted));

        // the author AND the directly-mentioned user joined
        verify(participantRepository, org.mockito.Mockito.times(2))
                .save(any(oak.server.entity.ChannelParticipant.class));
        assertEquals(Instant.parse("2026-09-22T02:00:00Z"), discussion.getLastMessageAt());
        ArgumentCaptor<DiscussionEvent> event = ArgumentCaptor.forClass(DiscussionEvent.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/channel.7.discussions"), event.capture());
        assertEquals(DiscussionEvent.TYPE_ACTIVITY, event.getValue().type());
        assertEquals(77L, event.getValue().discussionId());
        assertEquals(4L, event.getValue().replyCount());
        assertEquals("bob", event.getValue().actorUsername());
        // the posted MESSAGE rides the event — clients never subscribed to the thread's own
        // topic run mention detection over exactly this row
        assertEquals(wireMessage, event.getValue().message());
    }

    @Test
    void closeTearsDownInFkSafeOrderAndPublishesRemoved() {
        Channel thread = new Channel("discussion-1", ChannelType.DISCUSSION);
        thread.setId(77L);
        Discussion discussion = new Discussion(77L, 7L, 100L, "s", 5L, "alice", Instant.now());
        User actor = new User("alice", "hash");
        when(discussionRepository.findByChannelId(77L)).thenReturn(Optional.of(discussion));
        when(channelRepository.findById(77L)).thenReturn(Optional.of(thread));
        when(auth.requireUser("alice")).thenReturn(actor);
        when(messageRepository.findByChannelId(77L)).thenReturn(List.of());

        discussionService.close(77L, "alice");

        InOrder order = inOrder(reactionRepository, messageRepository, participantRepository,
                readStateRepository, discussionRepository, channelRepository);
        order.verify(reactionRepository).deleteByChannelId(77L);
        order.verify(messageRepository).deleteByChannelId(77L);
        order.verify(participantRepository).deleteByChannelId(77L);
        order.verify(readStateRepository).deleteByChannelId(77L);
        order.verify(discussionRepository).delete(discussion);
        order.verify(channelRepository).delete(thread);
        ArgumentCaptor<DiscussionEvent> event = ArgumentCaptor.forClass(DiscussionEvent.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/channel.7.discussions"), event.capture());
        assertEquals(DiscussionEvent.TYPE_REMOVED, event.getValue().type());
        assertNull(event.getValue().discussion()); // the row is gone — only the ids ride the push
    }

    @Test
    void closeRequiresManageMessagesThroughTheParentDelegation() {
        Channel thread = new Channel("discussion-1", ChannelType.DISCUSSION);
        thread.setId(77L);
        Discussion discussion = new Discussion(77L, 7L, 100L, "s", 5L, "alice", Instant.now());
        User actor = new User("bob", "hash");
        when(discussionRepository.findByChannelId(77L)).thenReturn(Optional.of(discussion));
        when(channelRepository.findById(77L)).thenReturn(Optional.of(thread));
        when(auth.requireUser("bob")).thenReturn(actor);
        org.mockito.Mockito.doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "no"))
                .when(auth).requireInChannel(actor, thread, Permission.MANAGE_MESSAGES);

        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> discussionService.close(77L, "bob")).getStatusCode());
        verify(discussionRepository, never()).delete(any(Discussion.class));
    }
}
