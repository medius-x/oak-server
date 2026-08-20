package oak.server.service;

import lombok.RequiredArgsConstructor;
import oak.server.dto.CreateMessageRequest;
import oak.server.dto.EditMessageRequest;
import oak.server.dto.MessageResponse;
import oak.server.dto.MentionResponse;
import oak.server.dto.ReactionEvent;
import oak.server.dto.ReactionSummary;
import oak.server.entity.Channel;
import oak.server.entity.ChannelType;
import oak.server.entity.Message;
import oak.server.entity.MessageReaction;
import oak.server.entity.Permission;
import oak.server.entity.User;
import oak.server.repository.ChannelRepository;
import oak.server.repository.MessageReactionRepository;
import oak.server.repository.MessageRepository;
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

    private final MessageRepository messageRepository;
    private final ChannelRepository channelRepository;
    private final UserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final AttachmentUrlSigner attachmentUrlSigner;
    private final AuthorizationService auth;
    private final MentionParser mentionParser;
    private final MessageReactionRepository reactionRepository;

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
        Message message = messageRepository.save(
                new Message(channel, author, content, attachmentKey, request.attachmentFileName()));
        MessageResponse response = toResponse(message, List.of());
        // Variant A: written via REST, then broadcast to everyone subscribed to this channel.
        messagingTemplate.convertAndSend(CHANNEL_TOPIC_PREFIX + channelId, response);
        return response;
    }

    /** Returns the most recent {@code limit} messages of a channel, in chronological order. */
    @Transactional(readOnly = true)
    public List<MessageResponse> listByChannel(Long channelId, Integer limit, String viewerUsername) {
        Channel channel = channelRepository.findById(channelId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found"));
        auth.requireInChannel(auth.requireUser(viewerUsername), channel, Permission.READ_MESSAGE_HISTORY);
        List<Message> messages = new ArrayList<>(messageRepository
                .findByChannelIdOrderByIdDesc(channelId, PageRequest.of(0, clamp(limit)))
                .getContent());
        Collections.reverse(messages);
        // One batched reaction query for the whole page (toResponse never touches the repository
        // itself — otherwise a 50-message page would fire 50 extra selects).
        Map<Long, List<ReactionSummary>> reactionsByMessage = reactionSummariesByMessage(
                messages.stream().map(Message::getId).toList());
        return messages.stream()
                .map(message -> toResponse(message, reactionsByMessage.getOrDefault(message.getId(), List.of())))
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
        // snapshot, or every client applying the edit would wipe its rendered chips.
        MessageResponse response = toResponse(messageRepository.save(message),
                reactionSummaries(message.getId()));
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
     *  broadcast and history fetch). Reactions are passed IN by the caller — this method never
     *  touches the reaction repository, or a 50-message history page would fire 50 extra
     *  selects (callers batch via {@link #reactionSummariesByMessage}). */
    private MessageResponse toResponse(Message message, List<ReactionSummary> reactions) {
        String attachmentKey = message.getAttachmentKey();
        String attachmentUrl = isBlank(attachmentKey) ? null : attachmentUrlSigner.presignedGetUrl(attachmentKey);
        List<MentionResponse> mentions = mentionParser.parse(
                message.getContent(), message.getAuthor(), message.getChannel());
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
                reactions);
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
