package oak.server.service;

import lombok.RequiredArgsConstructor;
import oak.server.dto.CreateMessageRequest;
import oak.server.dto.EditMessageRequest;
import oak.server.dto.ForwardInfo;
import oak.server.dto.ForwardMessageRequest;
import oak.server.dto.MessageResponse;
import oak.server.dto.MentionResponse;
import oak.server.dto.ReactionEvent;
import oak.server.dto.ReactionSummary;
import oak.server.dto.ReplyInfo;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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
     *  mapping for rendering. The set mirrors the user's reference picker: 💯 👍 👎 ❤ 👉 👀 😢
     *  😄 ✅ ❌. */
    static final Set<String> REACTION_SLUGS = Set.of(
            "hundred", "thumbsup", "thumbsdown", "heart", "point", "eyes", "cry", "smile",
            "check", "cross");

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    /** Cap of the one-line reply-quote preview (chars, "…" appended when trimmed). */
    private static final int REPLY_PREVIEW_MAX = 100;

    private final MessageRepository messageRepository;
    private final ChannelRepository channelRepository;
    private final UserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final AttachmentUrlSigner attachmentUrlSigner;
    private final AuthorizationService auth;
    private final MentionParser mentionParser;
    private final MessageReactionRepository reactionRepository;
    private final ServerProfileRepository serverProfileRepository;

    @Transactional
    public MessageResponse create(Long channelId, CreateMessageRequest request, String authorUsername) {
        String content = request.content();
        String attachmentKey = request.attachmentKey();
        if (isBlank(content) && isBlank(attachmentKey)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message must have content or an attachment");
        }
        Channel channel = channelRepository.findById(channelId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found"));
        User author = userRepository.findByUsername(authorUsername)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Author not found"));
        // Channel-scoped SEND_MESSAGES (respects overrides; OWNER/ADMINISTRATOR bypass).
        auth.requireInChannel(author, channel, Permission.SEND_MESSAGES);
        // Posting a message that carries an attachment additionally requires ATTACH_FILES (channel-scoped).
        if (!isBlank(attachmentKey)) {
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
        Message message = new Message(channel, author, content, attachmentKey, request.attachmentFileName());
        if (replyTarget != null) {
            message.setRepliedToId(replyTarget.getId());
        }
        message = messageRepository.save(message);
        MessageResponse response = toResponse(message, List.of(),
                replyTarget == null ? Map.of() : Map.of(replyTarget.getId(), replyTarget));
        // Variant A: written via REST, then broadcast to everyone subscribed to this channel.
        messagingTemplate.convertAndSend(CHANNEL_TOPIC_PREFIX + channelId, response);
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
        Channel channel = channelRepository.findById(channelId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found"));
        auth.requireInChannel(auth.requireUser(viewerUsername), channel, Permission.READ_MESSAGE_HISTORY);
        List<Message> messages = new ArrayList<>(beforeId == null
                ? messageRepository.findByChannelIdOrderByIdDesc(channelId, PageRequest.of(0, clamp(limit)))
                        .getContent()
                : messageRepository.findByChannelIdAndIdLessThanOrderByIdDesc(
                        channelId, beforeId, PageRequest.of(0, clamp(limit))).getContent());
        Collections.reverse(messages);
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

    /** Replaces a message's text. Only the author (a server channel additionally allows
     *  MANAGE_MESSAGES holders — see {@link #requireCanModify}). Attachments are not editable:
     *  an attachment-only message simply gains text. {@code editedAt} starts stamping here.
     *  Broadcast mirrors {@link #create}: REST write → WS push on the {@code .edited} topic. */
    @Transactional
    public MessageResponse edit(Long channelId, Long messageId, EditMessageRequest request, String actorUsername) {
        Channel channel = channelRepository.findById(channelId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found"));
        Message message = messageRepository.findByIdAndChannelId(messageId, channelId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Message not found"));
        User actor = auth.requireUser(actorUsername);
        requireCanModify(actor, channel, message);
        message.setContent(request.content());
        message.setEditedAt(Instant.now());
        // The .edited push carries the FULL message view — including the current reaction
        // snapshot, or every client applying the edit would wipe its rendered chips. Same for
        // the reply quote when the edited message is itself a reply.
        MessageResponse response = toResponse(messageRepository.save(message),
                reactionSummaries(message.getId()), replyOriginalsFor(List.of(message)));
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
        // No ON DELETE CASCADE under ddl-auto=update — reaction rows must go first.
        reactionRepository.deleteByMessageId(message.getId());
        messageRepository.delete(message);
        messagingTemplate.convertAndSend(CHANNEL_TOPIC_PREFIX + channelId + DELETED_TOPIC_SUFFIX, messageId);
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
     *  {@link ForwardMessageRequest}. Gates: READ_MESSAGE_HISTORY on the source channel (mode a
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
        String attachmentKey = request.attachmentKey();
        String attachmentFileName = request.attachmentFileName();
        String originServer;
        String originChannel;
        Long originAuthorId;
        String originAuthorName;
        Instant originCreatedAt;
        boolean trusted;
        if (request.sourceMessageId() != null) {
            Message source = messageRepository.findById(request.sourceMessageId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Source message not found"));
            auth.requireInChannel(actor, source.getChannel(), Permission.READ_MESSAGE_HISTORY);
            content = source.getContent();
            attachmentKey = source.getAttachmentKey();
            attachmentFileName = source.getAttachmentFileName();
            originServer = serverProfileRepository.singleton().getName();
            originChannel = source.getChannel().getName();
            originAuthorId = source.getAuthor().getId();
            originAuthorName = source.getAuthor().getUsername();
            originCreatedAt = source.getCreatedAt();
            trusted = true;
        } else {
            originServer = request.originServer();
            originChannel = request.originChannel();
            originAuthorId = request.originAuthorId();
            originAuthorName = request.originAuthorName();
            originCreatedAt = request.originCreatedAt();
            trusted = false;
        }
        if (isBlank(content) && isBlank(attachmentKey)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Forward must have content or an attachment");
        }
        if (!isBlank(attachmentKey)) {
            auth.requireInChannel(actor, targetChannel, Permission.ATTACH_FILES);
        }
        Message forward = new Message(targetChannel, actor, content, attachmentKey, attachmentFileName);
        forward.setForwardOriginServer(originServer);
        forward.setForwardOriginChannel(originChannel);
        forward.setForwardAuthorId(originAuthorId);
        forward.setForwardAuthorName(originAuthorName);
        forward.setForwardCreatedAt(originCreatedAt);
        forward.setForwardTrusted(trusted);
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

    /** Builds the response view, signing a fresh GET URL when the message has an attachment and
     *  parsing {@code @}-mentions from the content (single chokepoint: covers both the create
     *  broadcast and history fetch). Reactions and reply originals are passed IN by the caller —
     *  this method never touches a repository, or a 50-message history page would fire 50 extra
     *  selects (callers batch via {@link #reactionSummariesByMessage} / {@link #replyOriginalsFor}). */
    private MessageResponse toResponse(Message message, List<ReactionSummary> reactions,
                                       Map<Long, Message> replyOriginals) {
        String attachmentKey = message.getAttachmentKey();
        String attachmentUrl = isBlank(attachmentKey) ? null : attachmentUrlSigner.presignedGetUrl(attachmentKey);
        ReplyInfo reply = replyInfo(message, replyOriginals);
        List<MentionResponse> mentions = withReplyMention(message, mentionParser.parse(
                message.getContent(), message.getAuthor(), message.getChannel()), reply);
        return new MessageResponse(
                message.getId(),
                message.getChannel().getId(),
                message.getAuthor().getId(),
                message.getAuthor().getUsername(),
                message.getContent(),
                message.getCreatedAt(),
                message.getAttachmentFileName(),
                attachmentUrl,
                mentions,
                message.getEditedAt(),
                reactions,
                reply,
                forwardInfo(message));
    }

    /** The forward's origin card, or null when the message is not a forward. Any single origin
     *  column set ⇒ a forward (they are written together in {@link #forward}). */
    private static ForwardInfo forwardInfo(Message message) {
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
                Boolean.TRUE.equals(message.getForwardTrusted()));
    }

    /** Batched reply-originals for a page of messages: one {@code findAllById} instead of one
     *  select per reply (mirrors {@link #reactionSummariesByMessage}). Originals missing from the
     *  result (deleted) are simply absent — {@link #replyInfo} turns that into the deleted marker. */
    private Map<Long, Message> replyOriginalsFor(List<Message> messages) {
        Set<Long> ids = messages.stream()
                .map(Message::getRepliedToId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return messageRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Message::getId, original -> original));
    }

    /** The message's reply quote: null when it is not a reply; {@code deleted=true} with null
     *  fields when the original is gone (dangling id, no FK); otherwise the LIVE original's
     *  snapshot — edits to the original show up in quotes on every re-fetch. */
    private static ReplyInfo replyInfo(Message message, Map<Long, Message> replyOriginals) {
        Long repliedToId = message.getRepliedToId();
        if (repliedToId == null) {
            return null;
        }
        Message original = replyOriginals.get(repliedToId);
        if (original == null) {
            return new ReplyInfo(repliedToId, null, null, null, null, true);
        }
        return new ReplyInfo(
                repliedToId,
                original.getAuthor().getId(),
                original.getAuthor().getUsername(),
                previewOf(original),
                original.getCreatedAt(),
                false);
    }

    /** One-line quote preview of the original: newlines flatten to spaces, capped at
     *  {@link #REPLY_PREVIEW_MAX} chars ("…" appended when trimmed); an attachment-only original
     *  falls back to its file name. */
    private static String previewOf(Message original) {
        String body = original.getContent();
        if (body == null || body.isBlank()) {
            body = original.getAttachmentFileName() == null ? "" : original.getAttachmentFileName();
        }
        String flat = body.replace('\n', ' ');
        return flat.length() <= REPLY_PREVIEW_MAX ? flat : flat.substring(0, REPLY_PREVIEW_MAX) + "…";
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

    private static int clamp(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
