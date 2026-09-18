package oak.server.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import oak.server.dto.AttachmentRef;
import oak.server.dto.AttachmentResponse;
import oak.server.dto.CreateMessageRequest;
import oak.server.dto.EditMessageRequest;
import oak.server.dto.ForwardInfo;
import oak.server.dto.ForwardMessageRequest;
import oak.server.dto.MessageResponse;
import oak.server.dto.MentionResponse;
import oak.server.dto.ReactionEvent;
import oak.server.dto.ReactionSummary;
import oak.server.dto.ReplyInfo;
import oak.server.dto.SearchMessagesResponse;
import oak.server.entity.Channel;
import oak.server.entity.ChannelType;
import oak.server.entity.Message;
import oak.server.entity.MessageReaction;
import oak.server.entity.Permission;
import oak.server.entity.User;
import oak.server.repository.ChannelRepository;
import oak.server.repository.MessageReactionRepository;
import oak.server.repository.MessageRepository;
import oak.server.repository.ServerProfileRepository;
import oak.server.repository.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {

    /** STOMP destination a channel's new messages are pushed to: {@code /topic/channel.{id}}. */
    public static final String CHANNEL_TOPIC_PREFIX = "/topic/channel.";

    /** Appended to {@link #CHANNEL_TOPIC_PREFIX}{id} for message-edit pushes (body: the full
     *  {@code MessageResponse}). A separate destination keeps an edit distinguishable from a new
     *  message — same payload type on the base topic would be ambiguous for the client. */
    public static final String EDITED_TOPIC_SUFFIX = ".edited";

    /** Appended to {@link #CHANNEL_TOPIC_PREFIX}{id} for message-delete pushes (body: the bare
     *  message id — mirrors {@code ChannelService.CHANNELS_DELETED_TOPIC}). */
    public static final String DELETED_TOPIC_SUFFIX = ".deleted";

    /** Appended to {@link #CHANNEL_TOPIC_PREFIX}{id} for reaction pushes (body: a
     *  {@link ReactionEvent} carrying the message's full reaction snapshot). */
    public static final String REACTIONS_TOPIC_SUFFIX = ".reactions";

    /** The full emoji catalog clients may react with, as URL/database-safe slugs (never glyphs).
     *  Any other slug is rejected with 400; the client mirrors this list with slug→glyph
     *  mapping for rendering. The set mirrors the user's reference picker: 💯 👍 👎 ❤ 👀 😢
     *  😄 🤦 ✅ ❌ (facepalm right before the check; the pointing finger left the set). */
    static final Set<String> REACTION_SLUGS = Set.of(
            "hundred", "thumbsup", "thumbsdown", "heart", "eyes", "cry", "smile",
            "facepalm", "check", "cross");

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    /** Search page sizing (Discord's result-page shape — smaller than a history page). */
    static final int SEARCH_DEFAULT_LIMIT = 25;
    static final int SEARCH_MAX_LIMIT = 50;

    /** Cap of the one-line reply-quote preview (chars, "…" appended when trimmed). */
    private static final int REPLY_PREVIEW_MAX = 100;

    /** How deep nested quote cards resolve/serialize: a reply chain renders as recursive cards
     *  (a reply to a reply to a …), and past this depth the tree is TRUNCATED (nested null) —
     *  not marked deleted. Bounds both the per-page level-batch in {@link #replyOriginalsFor}
     *  and every persisted forward snapshot (see {@link NestedReplyCodec#capDepth}). */
    static final int MAX_QUOTE_DEPTH = 5;

    /** Hard cap of attachments per message (create AND the cross-instance forward snapshot) —
     *  the Discord limit. Enforced here at the service boundary; a stored row can never exceed
     *  it because writes only go through {@link #create}/{@link #forward}. */
    public static final int MAX_ATTACHMENTS = 10;

    private final MessageRepository messageRepository;
    private final ChannelRepository channelRepository;
    private final UserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final AttachmentUrlSigner attachmentUrlSigner;
    private final AuthorizationService auth;
    private final MentionParser mentionParser;
    private final MessageReactionRepository reactionRepository;
    private final ServerProfileRepository serverProfileRepository;
    private final NestedReplyCodec nestedReplyCodec;
    private final AttachmentListCodec attachmentListCodec;
    private final MessageIdempotencyCache idempotencyCache;
    private final AttachmentObjectStore objectStore;

    @Transactional
    public MessageResponse create(Long channelId, CreateMessageRequest request, String authorUsername) {
        // Idempotent replay FIRST (network resilience): a retry whose original response was lost
        // must get the ORIGINAL message back — no second row, no second broadcast. Checking before
        // the gates is safe because an entry can only exist for a send that ALREADY passed them
        // (entries are namespaced per author), and it keeps a replay immune to state that changed
        // after the original (e.g. its reply target since deleted).
        if (request.idempotencyKey() != null) {
            MessageResponse replay = idempotencyCache.get(authorUsername, request.idempotencyKey());
            if (replay != null) {
                log.info("idempotent replay: message {} re-served to {} by key {}",
                        replay.id(), authorUsername, request.idempotencyKey());
                return replay;
            }
        }
        String content = request.content();
        List<AttachmentRef> attachments = sanitized(request.attachments());
        if (isBlank(content) && attachments.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message must have content or an attachment");
        }
        Channel channel = channelRepository.findById(channelId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found"));
        User author = userRepository.findByUsername(authorUsername)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Author not found"));
        // Channel-scoped SEND_MESSAGES (respects overrides; OWNER/ADMINISTRATOR bypass).
        auth.requireInChannel(author, channel, Permission.SEND_MESSAGES);
        // Posting a message that carries attachments additionally requires ATTACH_FILES (channel-scoped).
        if (!attachments.isEmpty()) {
            auth.requireInChannel(author, channel, Permission.ATTACH_FILES);
        }
        // A reply must target an existing message of the SAME channel (cross-channel ids leak
        // nothing — plain 404, matching the findByIdAndChannelId scoping idiom).
        Message replyTarget = null;
        if (request.replyToId() != null) {
            replyTarget = messageRepository.findByIdAndChannelId(request.replyToId(), channelId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "Message to reply to not found"));
        }
        Message message = new Message(channel, author, content);
        // Set BEFORE save: toResponse below reads the JSON column off the in-memory entity, so
        // the create broadcast equals a later history fetch (the forwardNestedReply discipline).
        message.setAttachments(attachmentListCodec.write(attachments));
        if (replyTarget != null) {
            message.setRepliedToId(replyTarget.getId());
        }
        message = messageRepository.save(message);
        // The level-batch resolves the target AND its whole chain (up to MAX_QUOTE_DEPTH), so a
        // reply-to-a-reply's create broadcast already carries its nested cards — one code path
        // with history fetches, no depth-1 special case.
        MessageResponse response = toResponse(message, List.of(), replyOriginalsFor(List.of(message)));
        // Variant A: written via REST, then broadcast to everyone subscribed to this channel.
        messagingTemplate.convertAndSend(CHANNEL_TOPIC_PREFIX + channelId, response);
        if (request.idempotencyKey() != null) {
            // After the broadcast, so a thrown store problem can never double-send: the entry is
            // the very response that just went out.
            idempotencyCache.put(authorUsername, request.idempotencyKey(), response);
        }
        return response;
    }

    /** Returns the most recent {@code limit} messages of a channel, in chronological order. */
    @Transactional(readOnly = true)
    public List<MessageResponse> listByChannel(Long channelId, Integer limit, String viewerUsername) {
        return listByChannel(channelId, limit, null, viewerUsername);
    }

    /** Cursor flavour: {@code beforeId != null} pages strictly OLDER than that message (newest
     *  first internally, reversed to chronological — same contract as the plain flavour, just a
     *  different window). Same READ_MESSAGE_HISTORY gate. */
    @Transactional(readOnly = true)
    public List<MessageResponse> listByChannel(Long channelId, Integer limit, Long beforeId,
                                               String viewerUsername) {
        return listByChannel(channelId, limit, beforeId, null, null, viewerUsername);
    }

    /** Full cursor flavour: at most ONE of {@code beforeId}/{@code aroundId}/{@code afterId}
     *  (400 otherwise — combining them is meaningless). {@code aroundId} centers a window on the
     *  given message (the search-result jump: ~half the page older, the rest newer; short halves
     *  at the ends of history are fine); {@code afterId} pages strictly NEWER than the cursor,
     *  already in chronological order (no reverse). Same READ_MESSAGE_HISTORY gate. */
    @Transactional(readOnly = true)
    public List<MessageResponse> listByChannel(Long channelId, Integer limit, Long beforeId,
                                               Long aroundId, Long afterId, String viewerUsername) {
        int cursorCount = (beforeId == null ? 0 : 1) + (aroundId == null ? 0 : 1) + (afterId == null ? 0 : 1);
        if (cursorCount > 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Only one of beforeId, aroundId, afterId may be given");
        }
        Channel channel = channelRepository.findById(channelId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found"));
        auth.requireInChannel(auth.requireUser(viewerUsername), channel, Permission.READ_MESSAGE_HISTORY);
        List<Message> messages;
        if (aroundId != null) {
            messages = aroundWindow(channelId, aroundId, clamp(limit));
        } else if (afterId != null) {
            messages = new ArrayList<>(messageRepository.findByChannelIdAndIdGreaterThanOrderByIdAsc(
                    channelId, afterId, PageRequest.of(0, clamp(limit))));
        } else if (beforeId != null) {
            messages = new ArrayList<>(messageRepository.findByChannelIdAndIdLessThanOrderByIdDesc(
                    channelId, beforeId, PageRequest.of(0, clamp(limit))).getContent());
            Collections.reverse(messages);
        } else {
            messages = new ArrayList<>(messageRepository.findByChannelIdOrderByIdDesc(
                    channelId, PageRequest.of(0, clamp(limit))).getContent());
            Collections.reverse(messages);
        }
        // One batched reaction query for the whole page (toResponse never touches the repository
        // itself — otherwise a 50-message page would fire 50 extra selects). Same discipline for
        // reply quotes: one findAllById for the page's originals.
        Map<Long, List<ReactionSummary>> reactionsByMessage = reactionSummariesByMessage(
                messages.stream().map(Message::getId).toList());
        Map<Long, Message> replyOriginals = replyOriginalsFor(messages);
        return messages.stream()
                .map(message -> toResponse(message, reactionsByMessage.getOrDefault(message.getId(), List.of()),
                        replyOriginals))
                .toList();
    }

    /** The {@code aroundId} window: {@code pageSize} messages centered on the target — the older
     *  half via the DESC page (reversed), the target, then the newer half via the ASC page. A
     *  message id from another channel 404s here ({@code findByIdAndChannelId} scoping — no
     *  cross-channel leak). */
    private List<Message> aroundWindow(Long channelId, Long aroundId, int pageSize) {
        Message target = messageRepository.findByIdAndChannelId(aroundId, channelId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Message not found"));
        int olderCount = (pageSize - 1) / 2;
        int newerCount = (pageSize - 1) - olderCount;
        List<Message> older = new ArrayList<>(olderCount == 0 ? List.<Message>of()
                : messageRepository.findByChannelIdAndIdLessThanOrderByIdDesc(
                        channelId, target.getId(), PageRequest.of(0, olderCount)).getContent());
        Collections.reverse(older);
        List<Message> newer = newerCount == 0 ? List.of()
                : messageRepository.findByChannelIdAndIdGreaterThanOrderByIdAsc(
                        channelId, target.getId(), PageRequest.of(0, newerCount));
        List<Message> window = new ArrayList<>(older.size() + 1 + newer.size());
        window.addAll(older);
        window.add(target);
        window.addAll(newer);
        return window;
    }

    /** Message search (Discord-style): substring match on the content and the attachments JSON
     *  (file names match), newest first, paged by {@code beforeId}. Scope: one channel
     *  ({@code channelId} given — a DM works, the gate degenerates to the participant check) or
     *  the whole server (every server TEXT channel the viewer can read; DM/group-DM channels are
     *  excluded BY TYPE — a server-wide search must never return anyone's DMs). {@code q} and
     *  {@code author} are both optional filters, at least one required; an unknown author yields
     *  an empty result (total 0) rather than 404 — no oracle for probing logins. */
    @Transactional(readOnly = true)
    public SearchMessagesResponse searchMessages(String q, Long channelId, String author,
                                                 Long beforeId, Integer limit, String viewerUsername) {
        boolean hasQuery = !isBlank(q);
        if (!hasQuery && isBlank(author)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide a query or an author filter");
        }
        User viewer = auth.requireUser(viewerUsername);
        List<Long> channelIds;
        if (channelId != null) {
            Channel channel = channelRepository.findById(channelId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found"));
            auth.requireInChannel(viewer, channel, Permission.READ_MESSAGE_HISTORY);
            channelIds = List.of(channelId);
        } else {
            // The ReadStateService.snapshot pattern: walk the non-DM channels, keep the ones the
            // viewer may read. hasInChannel's VIEW_CHANNEL master gate drops hidden channels.
            channelIds = channelRepository
                    .findByTypeNotInOrderByIdAsc(List.of(ChannelType.DM, ChannelType.GROUP_DM)).stream()
                    .filter(channel -> channel.getType() == ChannelType.TEXT)
                    .filter(channel -> auth.hasInChannel(viewer, channel, Permission.READ_MESSAGE_HISTORY))
                    .map(Channel::getId)
                    .toList();
        }
        long authorId = 0;
        if (!isBlank(author)) {
            authorId = userRepository.findByUsername(author).map(User::getId).orElse(0L);
        }
        if (channelIds.isEmpty() || (authorId == 0 && !isBlank(author))) {
            // No readable channel or an unknown author: zero hits — the empty IN-list would also
            // be invalid SQL, so this is a correctness guard, not an optimization.
            return new SearchMessagesResponse(0, List.of());
        }
        String pattern = likePattern(hasQuery ? q : null);
        long total = messageRepository.countSearchMessages(channelIds, authorId, pattern);
        List<Message> page = total == 0 ? List.of()
                : messageRepository.searchMessages(channelIds, authorId, pattern,
                        beforeId == null ? Long.MAX_VALUE : beforeId, clampSearch(limit));
        Map<Long, List<ReactionSummary>> reactionsByMessage = reactionSummariesByMessage(
                page.stream().map(Message::getId).toList());
        Map<Long, Message> replyOriginals = replyOriginalsFor(page);
        List<MessageResponse> results = page.stream()
                .map(message -> toResponse(message, reactionsByMessage.getOrDefault(message.getId(), List.of()),
                        replyOriginals))
                .toList();
        return new SearchMessagesResponse(total, results);
    }

    /** Replaces a message's text and/or prunes its attachment list (remove-only, Discord-style:
     *  {@code request.attachments()} must be a SUBSET of the currently attached keys). Only the
     *  author (a server channel additionally allows MANAGE_MESSAGES holders — see
     *  {@link #requireCanModify}). The content-or-attachment rule is enforced on the RESULTING
     *  message (blank text is legal while an attachment remains — removing the last attachment
     *  of an attachment-only message while clearing the text is not). {@code editedAt} starts
     *  stamping here. Broadcast mirrors {@link #create}: REST write → WS push on the
     *  {@code .edited} topic. The MinIO objects of removed attachments are NEVER deleted —
     *  intra-instance forwards share the keys. */
    @Transactional
    public MessageResponse edit(Long channelId, Long messageId, EditMessageRequest request, String actorUsername) {
        Channel channel = channelRepository.findById(channelId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found"));
        Message message = messageRepository.findByIdAndChannelId(messageId, channelId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Message not found"));
        User actor = auth.requireUser(actorUsername);
        requireCanModify(actor, channel, message);
        // Attachment rewrite first, VALIDATED before any mutation (a 400 must leave the row and
        // editedAt untouched): null = untouched; a list = exactly that list, each key one of
        // the currently attached ones, no duplicates. A stored row never exceeds the cap, so a
        // subset never does either; create's sanitized() is deliberately NOT reused — its
        // blank-key error message would mislead here (a blank key is simply not a subset).
        List<AttachmentRef> currentAttachments = attachmentListCodec.read(message.getAttachments());
        List<AttachmentRef> resultingAttachments = currentAttachments;
        if (request.attachments() != null) {
            Set<String> currentKeys = currentAttachments.stream()
                    .map(AttachmentRef::key).filter(Objects::nonNull).collect(Collectors.toSet());
            Set<String> seenKeys = new java.util.HashSet<>();
            for (AttachmentRef ref : request.attachments()) {
                if (ref == null || ref.key() == null || !currentKeys.contains(ref.key())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Unknown attachment: " + (ref == null ? null : ref.key()));
                }
                if (!seenKeys.add(ref.key())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Duplicate attachment: " + ref.key());
                }
            }
            resultingAttachments = List.copyOf(request.attachments());
        }
        if (isBlank(request.content()) && resultingAttachments.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Message must have content or an attachment");
        }
        message.setContent(request.content());
        Set<String> removedKeys = Set.of();
        if (request.attachments() != null) {
            Set<String> resultingKeys = resultingAttachments.stream()
                    .map(AttachmentRef::key).filter(Objects::nonNull).collect(Collectors.toSet());
            removedKeys = currentAttachments.stream()
                    .map(AttachmentRef::key)
                    .filter(key -> key != null && !resultingKeys.contains(key))
                    .collect(Collectors.toSet());
            message.setAttachments(attachmentListCodec.write(resultingAttachments));
        }
        message.setEditedAt(Instant.now());
        // The .edited push carries the FULL message view — including the current reaction
        // snapshot, or every client applying the edit would wipe its rendered chips. Same for
        // the reply quote when the edited message is itself a reply.
        MessageResponse response = toResponse(messageRepository.save(message),
                reactionSummaries(message.getId()), replyOriginalsFor(List.of(message)));
        messageRepository.flush(); // the orphan check below must not see the pre-edit JSON
        deleteUnreferencedObjects(removedKeys);
        messagingTemplate.convertAndSend(CHANNEL_TOPIC_PREFIX + channelId + EDITED_TOPIC_SUFFIX, response);
        return response;
    }

    /** Removes a message outright (no tombstone — Discord-style, the row disappears for everyone).
     *  Same gate as {@link #edit}; pushes the bare message id on the {@code .deleted} topic. */
    @Transactional
    public void delete(Long channelId, Long messageId, String actorUsername) {
        Channel channel = channelRepository.findById(channelId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found"));
        Message message = messageRepository.findByIdAndChannelId(messageId, channelId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Message not found"));
        User actor = auth.requireUser(actorUsername);
        requireCanModify(actor, channel, message);
        // The attachments die with the message unless someone else still references them
        // (a forward shares the key) — collected BEFORE the row is gone.
        Set<String> attachmentKeys = attachmentListCodec.read(message.getAttachments()).stream()
                .flatMap(reference -> java.util.stream.Stream.of(reference.key(), reference.thumbnailKey()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        // No ON DELETE CASCADE under ddl-auto=update — reaction rows must go first.
        reactionRepository.deleteByMessageId(message.getId());
        messageRepository.delete(message);
        messageRepository.flush(); // the orphan check below must not see the deleted row
        deleteUnreferencedObjects(attachmentKeys);
        messagingTemplate.convertAndSend(CHANNEL_TOPIC_PREFIX + channelId + DELETED_TOPIC_SUFFIX, messageId);
    }

    /**
     * The event tier of storage GC: keys whose LAST reference just died are deleted from the
     * object store immediately. A key still referenced by any remaining message (typically a
     * forward's copy) stays — that is the whole correctness of sharing keys across messages.
     * Best-effort per key: an already-gone object logs and moves on.
     */
    private void deleteUnreferencedObjects(Set<String> keys) {
        for (String key : keys) {
            if (!attachmentExists(key)) {
                objectStore.delete(key);
                log.info("[storage-gc] object '{}' orphaned by its last reference — deleted", key);
            }
        }
    }

    /** Author-or-moderator gate shared by edit/delete. Own message: always allowed (the author
     *  proved SEND_MESSAGES when writing it; Discord lets you fix your own text). Someone else's
     *  message: a server channel requires MANAGE_MESSAGES (channel-scoped, override-aware,
     *  OWNER/ADMINISTRATOR bypass); a DM/group-DM NEVER allows it — {@code hasInChannel} grants
     *  every permission to ANY participant there, so consulting MANAGE_MESSAGES would let any
     *  participant rewrite everyone's DM messages. A former group-DM participant (the removed
     *  author) is denied by the participation check. */
    private void requireCanModify(User actor, Channel channel, Message message) {
        boolean own = actor.getId() != null && actor.getId().equals(message.getAuthor().getId());
        if (channel.getType() == ChannelType.DM || channel.getType() == ChannelType.GROUP_DM) {
            // The DM branch of hasInChannel is a pure participant check regardless of the permission.
            auth.requireInChannel(actor, channel, Permission.READ_MESSAGE_HISTORY);
            if (!own) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Only the author can modify a DM message");
            }
            return;
        }
        if (!own) {
            auth.requireInChannel(actor, channel, Permission.MANAGE_MESSAGES);
        }
    }

    /** Copies a message into {@code channelId} as a new forward (Discord-style): the copy carries
     *  the original's content/attachment plus a denormalized origin card; the copy's author is the
     *  forwarder. Two modes — intra-instance ({@code sourceMessageId} set: the server derives the
     *  payload AND every origin field canonically, {@code trusted=true}) and cross-instance
     *  snapshot (client-supplied payload+origin on trust, {@code trusted=false}) — see
     *  {@link ForwardMessageRequest}. In mode (a) a non-null {@code request.attachments()} selects
     *  a SUBSET of the source's attachments (the image viewer's "forward the image": only the
     *  chosen files are copied and the source text is dropped); null copies everything (legacy
     *  behaviour). Gates: READ_MESSAGE_HISTORY on the source channel (mode a
     *  only; in a DM that is the participant check), SEND_MESSAGES (+ ATTACH_FILES when the copy
     *  carries an attachment) on the TARGET channel. Broadcast rides the base channel topic — the
     *  full {@code MessageResponse} needs no topic of its own. A forward is never a reply
     *  (repliedToId stays null — the origin card replaces the quote). */
    @Transactional
    public MessageResponse forward(Long channelId, ForwardMessageRequest request, String actorUsername) {
        Channel targetChannel = channelRepository.findById(channelId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found"));
        User actor = userRepository.findByUsername(actorUsername)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Author not found"));
        auth.requireInChannel(actor, targetChannel, Permission.SEND_MESSAGES);

        String content = request.content();
        List<AttachmentRef> attachments;
        String originServer;
        String originChannel;
        Long originAuthorId;
        String originAuthorName;
        Instant originCreatedAt;
        boolean trusted;
        ReplyInfo originReply;
        if (request.sourceMessageId() != null) {
            Message source = messageRepository.findById(request.sourceMessageId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Source message not found"));
            auth.requireInChannel(actor, source.getChannel(), Permission.READ_MESSAGE_HISTORY);
            // Verbatim copy: keys are shared with the source (MinIO objects are never deleted
            // with messages), no per-attachment re-sign or upload. A stored row cannot exceed
            // MAX_ATTACHMENTS, so no cap re-check here.
            List<AttachmentRef> sourceAttachments = attachmentListCodec.read(source.getAttachments());
            if (request.attachments() != null) {
                // The image viewer's "forward the image": the client picked PART of the source's
                // attachments. Content is dropped — the source text was written about ALL the
                // images, and a non-empty subset keeps the content-or-attachment gate satisfied.
                attachments = subsetOf(request.attachments(), sourceAttachments);
                content = null;
            } else {
                attachments = sourceAttachments;
                content = source.getContent();
            }
            originServer = serverProfileRepository.singleton().getName();
            originChannel = source.getChannel().getName();
            originAuthorId = source.getAuthor().getId();
            originAuthorName = source.getAuthor().getUsername();
            originCreatedAt = source.getCreatedAt();
            trusted = true;
            // Forwarding a REPLY snapshots its quote tree: the copy keeps rendering the nested
            // cards the source carried (frozen — later edits of the source's own original never
            // propagate, the same snapshot semantics as every other origin field).
            originReply = source.getRepliedToId() == null ? null
                    : nestedReplyCodec.capDepth(
                            replyInfo(source, replyOriginalsFor(List.of(source)), MAX_QUOTE_DEPTH),
                            MAX_QUOTE_DEPTH);
        } else {
            originServer = request.originServer();
            originChannel = request.originChannel();
            originAuthorId = request.originAuthorId();
            originAuthorName = request.originAuthorName();
            originCreatedAt = request.originCreatedAt();
            trusted = false;
            attachments = sanitized(request.attachments());
            // The client-supplied tree rides the same trust model as every mode-b field; the
            // depth cap runs BEFORE persisting so a hostile payload cannot bloat the column.
            originReply = nestedReplyCodec.capDepth(request.originReply(), MAX_QUOTE_DEPTH);
        }
        if (isBlank(content) && attachments.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Forward must have content or an attachment");
        }
        if (!attachments.isEmpty()) {
            auth.requireInChannel(actor, targetChannel, Permission.ATTACH_FILES);
        }
        Message forward = new Message(targetChannel, actor, content);
        // Same set-before-save discipline as create (the broadcast reads the in-memory entity).
        forward.setAttachments(attachmentListCodec.write(attachments));
        forward.setForwardOriginServer(originServer);
        forward.setForwardOriginChannel(originChannel);
        forward.setForwardAuthorId(originAuthorId);
        forward.setForwardAuthorName(originAuthorName);
        forward.setForwardCreatedAt(originCreatedAt);
        forward.setForwardTrusted(trusted);
        // Set BEFORE save: toResponse below reads the field off the in-memory entity, so the
        // create broadcast equals a later history fetch byte for byte (the reactions' determinism
        // discipline). A write failure degrades to null — the forward must not fail over the
        // cosmetic nesting.
        forward.setForwardNestedReply(nestedReplyCodec.write(originReply));
        forward = messageRepository.save(forward);
        MessageResponse response = toResponse(forward, List.of(), Map.of());
        messagingTemplate.convertAndSend(CHANNEL_TOPIC_PREFIX + channelId, response);
        return response;
    }

    /** Adds the actor's reaction (idempotent — a repeated PUT is a no-op, the unique
     *  message+user+reaction constraint keeps one row). Gated by ADD_REACTIONS
     *  (channel-scoped, override-aware; in a DM/group-DM that check degenerates to the
     *  participant check — see {@code AuthorizationService.hasInChannel}). Returns and
     *  broadcasts the full deterministic snapshot so clients render exactly one truth whether
     *  they apply the REST response or the WS echo (or both). */
    @Transactional
    public List<ReactionSummary> addReaction(Long channelId, Long messageId, String slug, String actorUsername) {
        ResolvedReactionTarget target = resolveReactionTarget(channelId, messageId, slug, actorUsername);
        if (!reactionRepository.existsByMessageIdAndUserIdAndReaction(
                target.message().getId(), target.actor().getId(), slug)) {
            reactionRepository.save(new MessageReaction(target.message(), target.actor(), slug));
        }
        return pushReactionSnapshot(target.channel(), target.message());
    }

    /** Removes the actor's own reaction (idempotent — deleting a missing row is a no-op that
     *  still returns/broadcasts the snapshot). Same gate as {@link #addReaction}. */
    @Transactional
    public List<ReactionSummary> removeReaction(Long channelId, Long messageId, String slug, String actorUsername) {
        ResolvedReactionTarget target = resolveReactionTarget(channelId, messageId, slug, actorUsername);
        reactionRepository.deleteByMessageIdAndUserIdAndReaction(
                target.message().getId(), target.actor().getId(), slug);
        return pushReactionSnapshot(target.channel(), target.message());
    }

    private record ResolvedReactionTarget(Channel channel, Message message, User actor) {
    }

    /** Shared resolution + gate for add/remove: channel → message (cross-channel safe) → actor →
     *  ADD_REACTIONS (DM participant check in disguise) → slug whitelist. */
    private ResolvedReactionTarget resolveReactionTarget(Long channelId, Long messageId, String slug,
                                                         String actorUsername) {
        Channel channel = channelRepository.findById(channelId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found"));
        Message message = messageRepository.findByIdAndChannelId(messageId, channelId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Message not found"));
        User actor = auth.requireUser(actorUsername);
        auth.requireInChannel(actor, channel, Permission.ADD_REACTIONS);
        if (!REACTION_SLUGS.contains(slug)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown reaction: " + slug);
        }
        return new ResolvedReactionTarget(channel, message, actor);
    }

    private List<ReactionSummary> pushReactionSnapshot(Channel channel, Message message) {
        List<ReactionSummary> snapshot = reactionSummaries(message.getId());
        messagingTemplate.convertAndSend(CHANNEL_TOPIC_PREFIX + channel.getId() + REACTIONS_TOPIC_SUFFIX,
                new ReactionEvent(message.getId(), snapshot));
        return snapshot;
    }

    /** Aggregates one message's reaction rows into deterministic summaries (sorted by slug, ids
     *  in insertion order) — identical bytes for the REST answer and the WS echo. */
    private List<ReactionSummary> reactionSummaries(Long messageId) {
        return groupReactions(reactionRepository.findByMessageId(messageId)).getOrDefault(messageId, List.of());
    }

    /** Batched variant for a history page: one query, grouped per message. */
    private Map<Long, List<ReactionSummary>> reactionSummariesByMessage(List<Long> messageIds) {
        if (messageIds.isEmpty()) {
            return Map.of();
        }
        return groupReactions(reactionRepository.findByMessageIdIn(messageIds));
    }

    private static Map<Long, List<ReactionSummary>> groupReactions(List<MessageReaction> rows) {
        Map<Long, Map<String, List<MessageReaction>>> byMessage = new LinkedHashMap<>();
        for (MessageReaction row : rows) {
            byMessage.computeIfAbsent(row.getMessage().getId(), unused -> new LinkedHashMap<>())
                    .computeIfAbsent(row.getReaction(), unused -> new ArrayList<>())
                    .add(row);
        }
        return byMessage.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> entry.getValue().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                        .map(slugEntry -> {
                            List<MessageReaction> slugRows = slugEntry.getValue();
                            return new ReactionSummary(
                                    slugEntry.getKey(),
                                    slugRows.size(),
                                    slugRows.stream().map(row -> row.getUser().getId()).toList(),
                                    slugRows.stream().map(row -> row.getUser().getUsername()).toList());
                        })
                        .toList(),
                (left, right) -> left,
                LinkedHashMap::new));
    }

    /** Builds the response view, signing a fresh GET URL per attachment and parsing
     *  {@code @}-mentions from the content (single chokepoint: covers both the create broadcast
     *  and history fetch). Reactions and reply originals are passed IN by the caller — this
     *  method never touches a repository, or a 50-message history page would fire 50 extra
     *  selects (callers batch via {@link #reactionSummariesByMessage} / {@link #replyOriginalsFor});
     *  the attachments are a CPU-only JSON parse (see {@link AttachmentListCodec}). */
    private MessageResponse toResponse(Message message, List<ReactionSummary> reactions,
                                       Map<Long, Message> replyOriginals) {
        List<AttachmentResponse> attachments = attachmentListCodec.read(message.getAttachments()).stream()
                .map(ref -> new AttachmentResponse(ref.fileName(),
                        attachmentUrlSigner.presignedGetUrl(ref.key()), ref.key(),
                        ref.thumbnailKey(),
                        // The preview JPEG is re-signed like any attachment — a thumbnail key
                        // mentioned in the JSON column resolves through the same capability model.
                        ref.thumbnailKey() == null ? null
                                : attachmentUrlSigner.presignedGetUrl(ref.thumbnailKey()),
                        ref.durationMillis()))
                .toList();
        ReplyInfo reply = replyInfo(message, replyOriginals, MAX_QUOTE_DEPTH);
        List<MentionResponse> mentions = withReplyMention(message, mentionParser.parse(
                message.getContent(), message.getAuthor(), message.getChannel()), reply);
        return new MessageResponse(
                message.getId(),
                message.getChannel().getId(),
                message.getAuthor().getId(),
                message.getAuthor().getUsername(),
                message.getContent(),
                message.getCreatedAt(),
                attachments,
                mentions,
                message.getEditedAt(),
                reactions,
                reply,
                forwardInfo(message));
    }

    /** The forward's origin card, or null when the message is not a forward. Any single origin
     *  column set ⇒ a forward (they are written together in {@link #forward}). The nested quote
     *  snapshot ({@code ForwardInfo.reply}) is read from the JSON column — a CPU-only parse, so
     *  the "toResponse never touches the repository" invariant holds. */
    private ForwardInfo forwardInfo(Message message) {
        if (message.getForwardOriginServer() == null && message.getForwardOriginChannel() == null
                && message.getForwardAuthorId() == null && message.getForwardAuthorName() == null
                && message.getForwardCreatedAt() == null) {
            return null;
        }
        return new ForwardInfo(
                message.getForwardOriginServer(),
                message.getForwardOriginChannel(),
                message.getForwardAuthorId(),
                message.getForwardAuthorName(),
                message.getForwardCreatedAt(),
                Boolean.TRUE.equals(message.getForwardTrusted()),
                nestedReplyCodec.read(message.getForwardNestedReply()));
    }

    /** Level-batched reply-originals for a page of messages: the page's replies' originals, then
     *  THOSE originals' own originals, and so on — up to {@link #MAX_QUOTE_DEPTH} levels, one
     *  {@code findAllById} per level (mirrors {@link #reactionSummariesByMessage}; worst case 7
     *  selects per history page, and only when the page's replies form deep chains). A union map:
     *  {@link #replyInfo} recurses over it, any level's miss becoming that level's deleted
     *  marker. The containsKey filter dedups shared ancestors and guards against hypothetical
     *  cycles (a reply target must pre-exist, so ids strictly decrease — cycles are impossible
     *  via the API; this is defense in depth). Kept SEPARATE from ReadStateService's one-level
     *  {@code replyOriginalsOf}: that one feeds the mention counter's freshest-200 loop, where
     *  extra levels are pure query cost — do not "unify" them. */
    private Map<Long, Message> replyOriginalsFor(List<Message> messages) {
        Map<Long, Message> originals = new LinkedHashMap<>();
        Set<Long> frontier = messages.stream()
                .map(Message::getRepliedToId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        for (int level = 0; level < MAX_QUOTE_DEPTH && !frontier.isEmpty(); level++) {
            frontier.removeAll(originals.keySet());
            if (frontier.isEmpty()) {
                break;
            }
            for (Message original : messageRepository.findAllById(frontier)) {
                originals.put(original.getId(), original);
            }
            frontier = originals.values().stream()
                    .map(Message::getRepliedToId)
                    .filter(Objects::nonNull)
                    .filter(id -> !originals.containsKey(id))
                    .collect(Collectors.toSet());
        }
        return originals.isEmpty() ? Map.of() : originals;
    }

    /** The message's reply quote: null when it is not a reply; {@code deleted=true} with null
     *  fields when the original is gone (dangling id, no FK); otherwise the LIVE original's
     *  snapshot — edits to the original show up in quotes on every re-fetch. A replied-to
     *  forward carries its origin snapshot ({@link #forwardInfo}) so the quote shows WHO was
     *  replied to (the forwarder) AND that the message was forwarded, not just its content.
     *  Recurses through {@code original}'s own quote ({@code ReplyInfo.reply}) so chains render
     *  as nested cards; {@code depth} counts DOWN from {@link #MAX_QUOTE_DEPTH} — at zero the
     *  nesting is truncated (null), which clients must not read as deleted. */
    private ReplyInfo replyInfo(Message message, Map<Long, Message> replyOriginals, int depth) {
        Long repliedToId = message.getRepliedToId();
        if (repliedToId == null) {
            return null;
        }
        Message original = replyOriginals.get(repliedToId);
        if (original == null) {
            return new ReplyInfo(repliedToId, null, null, null, null, true, null, null);
        }
        return new ReplyInfo(
                repliedToId,
                original.getAuthor().getId(),
                original.getAuthor().getUsername(),
                previewOf(original),
                original.getCreatedAt(),
                false,
                forwardInfo(original),
                depth > 1 ? replyInfo(original, replyOriginals, depth - 1) : null);
    }

    /** One-line quote preview of the original: newlines flatten to spaces, capped at
     *  {@link #REPLY_PREVIEW_MAX} chars ("…" appended when trimmed); an attachment-only original
     *  falls back to its FIRST file name. Instance method — reading the attachments JSON column
     *  needs the codec. */
    private String previewOf(Message original) {
        String body = original.getContent();
        if (body == null || body.isBlank()) {
            List<AttachmentRef> refs = attachmentListCodec.read(original.getAttachments());
            body = refs.isEmpty() ? "" : String.valueOf(refs.get(0).fileName());
        }
        String flat = body.replace('\n', ' ');
        return flat.length() <= REPLY_PREVIEW_MAX ? flat : flat.substring(0, REPLY_PREVIEW_MAX) + "…";
    }

    /** Normalizes an incoming attachment list: null → empty, null ELEMENTS dropped, and a
     *  blank {@code key} element is a 400 (the key is the only thing the server cannot invent —
     *  the client must have uploaded the bytes and minted it first). Enforces
     *  {@link #MAX_ATTACHMENTS} the same way. */
    private List<AttachmentRef> sanitized(List<AttachmentRef> attachments) {
        if (attachments == null) {
            return List.of();
        }
        List<AttachmentRef> kept = attachments.stream().filter(Objects::nonNull).toList();
        if (kept.size() > MAX_ATTACHMENTS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "At most " + MAX_ATTACHMENTS + " attachments per message");
        }
        for (AttachmentRef ref : kept) {
            if (ref.key() == null || ref.key().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Attachment is missing its key (upload the file first)");
            }
        }
        return kept;
    }

    /** Validates a client-selected attachment subset for an intra-instance forward (the image
     *  viewer's "forward the image"): every key must be one of the SOURCE's current keys (the
     *  picker can only choose what the message carries — a foreign key would smuggle an unlisted
     *  MinIO object into a new message), no duplicates, and the list must be non-empty (the
     *  subset mode drops the content, so an empty subset would violate content-or-attachment —
     *  fail here with a clear 400 instead of the generic one at the bottom of {@code forward}).
     *  The edit() subset checks are the direct precedent. */
    private List<AttachmentRef> subsetOf(List<AttachmentRef> requested, List<AttachmentRef> sourceAttachments) {
        if (requested.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Forward must select at least one attachment");
        }
        Set<String> sourceKeys = sourceAttachments.stream()
                .map(AttachmentRef::key).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<String> seenKeys = new HashSet<>();
        for (AttachmentRef ref : requested) {
            if (ref == null || ref.key() == null || !sourceKeys.contains(ref.key())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Unknown attachment: " + (ref == null ? null : ref.key()));
            }
            if (!seenKeys.add(ref.key())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Duplicate attachment: " + ref.key());
            }
        }
        return List.copyOf(requested);
    }

    /** Does any message reference this attachment key? Backs the fresh-link re-mint endpoint:
     *  an unknown key 404s there instead of handing out a signature for arbitrary input. The
     *  attachments column is JSON text and keys embed a random UUID, so the LIKE in the repo
     *  only ever matches a real reference. */
    public boolean attachmentExists(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        return messageRepository.countByAttachmentsTextLike("%" + key + "%") > 0;
    }

    /** Appends the reply ping (Discord-style): replying to someone's message mentions them
     *  without an {@code @token} in the text. Skipped for own messages, deleted originals, and
     *  when the parsed mentions already carry a USER mention of the same author (no double ping).
     *  Offsets are -1: the synthesized mention has no span to highlight in the content. */
    private static List<MentionResponse> withReplyMention(Message message, List<MentionResponse> parsed,
                                                           ReplyInfo reply) {
        if (reply == null || reply.deleted() || reply.authorId() == null
                || reply.authorId().equals(message.getAuthor().getId())) {
            return parsed;
        }
        boolean alreadyMentioned = parsed.stream()
                .anyMatch(mention -> "USER".equals(mention.type()) && reply.authorId().equals(mention.id()));
        if (alreadyMentioned) {
            return parsed;
        }
        List<MentionResponse> out = new ArrayList<>(parsed);
        out.add(new MentionResponse("USER", reply.authorId(), reply.authorUsername(), -1, -1));
        return out;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Wraps the search query into a LIKE pattern ({@code %…%}) with the LIKE metacharacters
     *  escaped — the user's input is a literal substring (Discord-style), never a wildcard
     *  expression. Backslash is the default LIKE escape character in both PostgreSQL and H2,
     *  so no ESCAPE clause is needed. A blank query becomes match-all ({@code %%}) — the
     *  author-only search. */
    static String likePattern(String q) {
        if (q == null || q.isBlank()) {
            return "%%";
        }
        return "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static int clamp(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static int clampSearch(Integer limit) {
        if (limit == null || limit <= 0) {
            return SEARCH_DEFAULT_LIMIT;
        }
        return Math.min(limit, SEARCH_MAX_LIMIT);
    }
}
