package oak.server.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import oak.server.dto.AttachmentRef;
import oak.server.dto.CreateDiscussionRequest;
import oak.server.dto.CreateMessageRequest;
import oak.server.dto.DiscussionEvent;
import oak.server.dto.DiscussionResponse;
import oak.server.dto.MentionResponse;
import oak.server.entity.Channel;
import oak.server.entity.ChannelParticipant;
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
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Discussions (threads): messages tied to one origin message of a TEXT channel, never appearing
 * in that channel's own history. A discussion is a {@link Channel} of type {@code DISCUSSION}
 * (the thread's messages are ORDINARY messages in it — history/edit/delete/reactions/forwards
 * all work through the existing machinery) plus a {@link Discussion} row freezing the origin
 * snapshot (the ForwardInfo discipline: the tile survives the origin's deletion or GC).
 *
 * <p><b>Permissions</b> resolve through the PARENT channel (the delegation branch in
 * {@code AuthorizationService.hasInChannel} — one hop, parents are always TEXT). <b>Tracking</b>
 * (the sidebar's "discussions I follow") is {@code ChannelParticipant} rows on the discussion
 * channel: the creator and every poster are auto-joined ("wrote once → follows"); anyone who can
 * read the parent may track/untrack explicitly.
 *
 * <p><b>Events</b> ride the parent channel's {@code /topic/channel.{parent}.discussions} topic
 * (CREATED / ACTIVITY / REMOVED — see {@link DiscussionEvent}); the suffix is registered in the
 * subscription interceptor's regex. Closing deletes everything (channel, messages, participants,
 * read states, the discussion row) — no archive, the Discord-style hard delete.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiscussionService {

    /** Appended to {@code /topic/channel.{parent}} for discussion lifecycle pushes. */
    public static final String DISCUSSIONS_TOPIC_SUFFIX = ".discussions";

    /** The frozen origin snippet budget (chars) — the tile shows two lines, the wire keeps it bounded. */
    static final int ORIGIN_SNIPPET_MAX = 200;

    private final ChannelRepository channelRepository;
    private final DiscussionRepository discussionRepository;
    private final MessageRepository messageRepository;
    private final MessageReactionRepository reactionRepository;
    private final ChannelParticipantRepository participantRepository;
    private final ChannelReadStateRepository readStateRepository;
    private final UserRepository userRepository;
    private final AuthorizationService auth;
    private final SimpMessagingTemplate messagingTemplate;
    private final AttachmentObjectStore objectStore;
    private final AttachmentListCodec attachmentListCodec;
    private final MentionParser mentionParser;
    /** The lazy-create flow posts the first message through the ordinary create path — the
     *  "post landed" hook comes back as a {@link MessagePostedInDiscussion} EVENT (decoupled,
     *  no bean cycle both ways). */
    private final MessageService messageService;

    // --- lifecycle ---

    /** Starts a discussion on one message of {@code parentChannelId}. Gates: the parent must
     *  be a TEXT channel (400 otherwise), the origin must live in it (404 — cross-channel ids
     *  leak nothing), the actor needs SEND_MESSAGES there, and the message must not already
     *  carry a discussion (409 — the client fetches the list and opens the existing one).
     *  The creator and the ORIGIN AUTHOR become the first participants (auto-track).
     *
     *  <p><b>Lazy creation:</b> with {@code content} (the first reply from the client's
     *  draft) the first message is posted into the thread in the SAME call — and a
     *  CONFLICT turns into "reuse the existing thread, post there anyway" (200), so the
     *  message is never lost to a 409 the draft cannot resolve. The response then carries
     *  {@code replyCount == 1}.
     */
    @Transactional
    public DiscussionResponse create(Long parentChannelId, CreateDiscussionRequest request, String actorUsername) {
        Channel parent = channelRepository.findById(parentChannelId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found"));
        if (parent.getType() != ChannelType.TEXT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Discussions start on text channels only");
        }
        Long messageId = request.messageId();
        Message origin = messageRepository.findByIdAndChannelId(messageId, parentChannelId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Message not found"));
        User actor = auth.requireUser(actorUsername);
        auth.requireInChannel(actor, parent, Permission.SEND_MESSAGES);

        Discussion existing = discussionRepository.findByOriginMessageId(messageId).orElse(null);
        final Discussion discussion;
        if (existing != null && request.hasFirstMessage()) {
            // Lost the create race WITH a first message aboard: post it into the winner.
            discussion = existing;
        } else if (existing != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A discussion already exists for this message");
        } else {
            Channel thread = channelRepository.save(new Channel("discussion-" + messageId, ChannelType.DISCUSSION));
            discussion = discussionRepository.save(new Discussion(
                    thread.getId(), parentChannelId, messageId,
                    snippetOf(origin), origin.getAuthor().getId(), origin.getAuthor().getUsername(),
                    origin.getCreatedAt()));
            participantRepository.save(new ChannelParticipant(thread, actor));
            // A thread on SOMEONE ELSE'S message auto-tracks the ORIGIN AUTHOR (the user
            // rule): the first reply must reach them like a mention — the ping fires
            // client-side off the ACTIVITY event, the participant row here is what puts the
            // thread in their "mine".
            User originAuthor = origin.getAuthor();
            if (!originAuthor.getId().equals(actor.getId())
                    && !participantRepository.existsByChannelIdAndUserId(thread.getId(), originAuthor.getId())) {
                participantRepository.save(new ChannelParticipant(thread, originAuthor));
            }
            // The broadcast row carries tracked=false for everyone (viewer-scoped by nature
            // — clients merge their own state); the REST answer carries the CREATOR's truth.
            publish(parentChannelId, new DiscussionEvent(DiscussionEvent.TYPE_CREATED, discussion.getChannelId(),
                    parentChannelId, messageId, discussion.getLastMessageAt(), 0, actorUsername,
                    DiscussionResponse.from(discussion, 0, false), null));
            log.info("discussion #{} started on message {} of channel {} by {}",
                    thread.getId(), messageId, parentChannelId, actorUsername);
        }
        if (request.hasFirstMessage()) {
            messageService.create(discussion.getChannelId(), new CreateMessageRequest(
                    request.content(), request.attachments(), null, request.idempotencyKey()), actorUsername);
        }
        return toResponse(discussion, actor);
    }

    /** Every discussion under one channel (any the viewer can read), each flagged tracked/not. */
    @Transactional(readOnly = true)
    public List<DiscussionResponse> listForParent(Long parentChannelId, String viewerUsername) {
        Channel parent = channelRepository.findById(parentChannelId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found"));
        User viewer = auth.requireUser(viewerUsername);
        auth.requireInChannel(viewer, parent, Permission.READ_MESSAGE_HISTORY);
        List<Discussion> discussions = discussionRepository.findByParentChannelId(parentChannelId);
        List<DiscussionResponse> out = new ArrayList<>(discussions.size());
        for (Discussion discussion : discussions) {
            out.add(toResponse(discussion, viewer));
        }
        return out;
    }

    /** The discussions THIS viewer follows, most recently active first. A discussion whose parent
     *  became unreadable (role pull) drops out — the delegation branch denies READ. */
    @Transactional(readOnly = true)
    public List<DiscussionResponse> listMine(String viewerUsername) {
        User viewer = auth.requireUser(viewerUsername);
        List<Long> trackedChannelIds = participantRepository.findChannelIdsByUserId(viewer.getId());
        if (trackedChannelIds.isEmpty()) {
            return List.of();
        }
        List<Discussion> candidates = discussionRepository.findByChannelIdIn(trackedChannelIds);
        List<DiscussionResponse> out = new ArrayList<>(candidates.size());
        for (Discussion discussion : candidates) {
            if (auth.hasInChannel(viewer, requireDiscussionChannel(discussion),
                    Permission.READ_MESSAGE_HISTORY)) {
                out.add(toResponse(discussion, viewer));
            }
        }
        out.sort(Comparator.comparing(DiscussionResponse::lastMessageAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    @Transactional(readOnly = true)
    public DiscussionResponse get(Long discussionId, String viewerUsername) {
        Discussion discussion = requireDiscussion(discussionId);
        User viewer = auth.requireUser(viewerUsername);
        auth.requireInChannel(viewer, requireDiscussionChannel(discussion), Permission.READ_MESSAGE_HISTORY);
        return toResponse(discussion, viewer);
    }

    /** Explicit follow (idempotent). Gate: READ on the parent — you cannot follow what you
     *  cannot read. */
    @Transactional
    public void track(Long discussionId, String actorUsername) {
        Discussion discussion = requireDiscussion(discussionId);
        Channel thread = requireDiscussionChannel(discussion);
        User actor = auth.requireUser(actorUsername);
        auth.requireInChannel(actor, thread, Permission.READ_MESSAGE_HISTORY);
        if (!participantRepository.existsByChannelIdAndUserId(thread.getId(), actor.getId())) {
            participantRepository.save(new ChannelParticipant(thread, actor));
        }
    }

    /** Unfollow (idempotent) — personal only: the discussion stays for everyone else. */
    @Transactional
    public void untrack(Long discussionId, String actorUsername) {
        Discussion discussion = requireDiscussion(discussionId);
        User actor = auth.requireUser(actorUsername);
        participantRepository.deleteByChannelIdAndUserId(discussion.getChannelId(), actor.getId());
    }

    /** Closes the discussion for EVERYONE: MANAGE_MESSAGES on the parent (via the delegation
     *  branch), full teardown (reactions → messages + orphan attachment GC → participants → read
     *  states → the discussion row → the channel), REMOVED broadcast on the parent topic. The
     *  push is the only close signal clients get — see {@link DiscussionEvent}. */
    @Transactional
    public void close(Long discussionId, String actorUsername) {
        Discussion discussion = requireDiscussion(discussionId);
        Channel thread = channelRepository.findById(discussion.getChannelId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Discussion not found"));
        User actor = auth.requireUser(actorUsername);
        auth.requireInChannel(actor, thread, Permission.MANAGE_MESSAGES);
        deleteInternals(discussion);
        publish(discussion.getParentChannelId(), new DiscussionEvent(DiscussionEvent.TYPE_REMOVED,
                discussion.getChannelId(), discussion.getParentChannelId(),
                discussion.getOriginMessageId(), null, 0, actorUsername, null, null));
        log.info("discussion #{} closed by {}", discussion.getChannelId(), actorUsername);
    }

    /** {@code MessageService.create} publishes {@link MessagePostedInDiscussion} after the
     *  base-topic broadcast; this listener is the hook (an EVENT, not a call — this service
     *  depends on MessageService for the lazy-create flow, a back-call would close the bean
     *  cycle). Auto-joins the author ("wrote once → follows") AND every directly @mentioned
     *  user (a mention in a thread subscribes the mentioned user — the user rule), refreshes
     *  {@code lastMessageAt}, and pushes ACTIVITY with the fresh FULL row, the actor and the
     *  posted MESSAGE (clients that never subscribed to the thread's own topic run mention
     *  detection over it; the origin author's client turns the FIRST reply into a ping). */
    @EventListener
    @Transactional
    public void onMessagePostedInDiscussion(MessagePostedInDiscussion event) {
        Channel thread = event.channel();
        Message posted = event.message();
        discussionRepository.findByChannelId(thread.getId()).ifPresent(discussion -> {
            if (!participantRepository.existsByChannelIdAndUserId(thread.getId(), posted.getAuthor().getId())) {
                participantRepository.save(new ChannelParticipant(thread, posted.getAuthor()));
            }
            joinMentionedUsers(thread, posted);
            discussion.setLastMessageAt(posted.getCreatedAt());
            discussionRepository.save(discussion);
            long replyCount = messageRepository.countByChannelId(thread.getId());
            publish(discussion.getParentChannelId(), new DiscussionEvent(DiscussionEvent.TYPE_ACTIVITY,
                    discussion.getChannelId(), discussion.getParentChannelId(),
                    discussion.getOriginMessageId(), discussion.getLastMessageAt(), replyCount,
                    posted.getAuthor().getUsername(),
                    DiscussionResponse.from(discussion, replyCount, false),
                    messageService.responseOf(posted)));
        });
    }

    /** Directly @mentioned users join the thread (auto-track). Deliberately USER-type only:
     *  an @role/@everyone in a thread notifies (the event carries the message), but does not
     *  subscribe the whole server to it — threads are opt-in surfaces. */
    private void joinMentionedUsers(Channel thread, Message posted) {
        List<MentionResponse> mentions = mentionParser.parse(posted.getContent(), posted.getAuthor(), thread);
        List<Long> mentionedIds = mentions.stream()
                .filter(mention -> "USER".equals(mention.type()) && mention.id() != null)
                .map(MentionResponse::id)
                .filter(id -> !id.equals(posted.getAuthor().getId()))
                .toList();
        if (mentionedIds.isEmpty()) {
            return;
        }
        for (User mentioned : userRepository.findAllById(mentionedIds)) {
            if (!participantRepository.existsByChannelIdAndUserId(thread.getId(), mentioned.getId())) {
                participantRepository.save(new ChannelParticipant(thread, mentioned));
            }
        }
    }

    /** The shared teardown used by {@link #close} and the parent channel's deletion — order is
     *  FK-safe (reactions before messages) and orphan attachment keys die with their last
     *  reference (the event tier of the storage GC, inlined here to keep MessageService and
     *  DiscussionService decoupled — same 6 lines, no cycle between the services). */
    @Transactional
    public void deleteDiscussionsOf(Long parentChannelId) {
        for (Discussion discussion : discussionRepository.findByParentChannelId(parentChannelId)) {
            deleteInternals(discussion);
        }
    }

    // --- helpers ---

    private void deleteInternals(Discussion discussion) {
        Long threadId = discussion.getChannelId();
        Set<String> attachmentKeys = new HashSet<>();
        for (Message message : messageRepository.findByChannelId(threadId)) {
            for (AttachmentRef reference : attachmentListCodec.read(message.getAttachments())) {
                if (reference.key() != null) {
                    attachmentKeys.add(reference.key());
                }
                if (reference.thumbnailKey() != null) {
                    attachmentKeys.add(reference.thumbnailKey());
                }
            }
        }
        reactionRepository.deleteByChannelId(threadId);
        messageRepository.deleteByChannelId(threadId);
        messageRepository.flush(); // the orphan check below must not see the deleted rows
        for (String key : attachmentKeys) {
            if (messageRepository.countByAttachmentsTextLike("%" + key + "%") == 0) {
                objectStore.delete(key);
            }
        }
        participantRepository.deleteByChannelId(threadId);
        readStateRepository.deleteByChannelId(threadId);
        discussionRepository.delete(discussion);
        channelRepository.findById(threadId).ifPresent(channelRepository::delete);
    }

    private Discussion requireDiscussion(Long discussionId) {
        // discussionId IS the discussion channel's id — resolve through the companion row.
        return discussionRepository.findByChannelId(discussionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Discussion not found"));
    }

    private Channel requireDiscussionChannel(Discussion discussion) {
        return channelRepository.findById(discussion.getChannelId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Discussion not found"));
    }

    private DiscussionResponse toResponse(Discussion discussion, User viewer) {
        long replyCount = messageRepository.countByChannelId(discussion.getChannelId());
        boolean tracked = participantRepository.existsByChannelIdAndUserId(discussion.getChannelId(), viewer.getId());
        return DiscussionResponse.from(discussion, replyCount, tracked);
    }

    private void publish(Long parentChannelId, DiscussionEvent event) {
        messagingTemplate.convertAndSend(
                "/topic/channel." + parentChannelId + DISCUSSIONS_TOPIC_SUFFIX, event);
    }

    private static String snippetOf(Message origin) {
        String body = origin.getContent();
        if (body == null || body.isBlank()) {
            return ""; // attachment-only origins render through the client's file-name fallback
        }
        String flat = body.replace('\n', ' ').trim();
        return flat.length() <= ORIGIN_SNIPPET_MAX ? flat : flat.substring(0, ORIGIN_SNIPPET_MAX) + "…";
    }
}
