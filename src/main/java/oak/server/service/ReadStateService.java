package oak.server.service;

import lombok.RequiredArgsConstructor;
import oak.server.dto.ChannelReadStateResponse;
import oak.server.dto.MentionResponse;
import oak.server.entity.Channel;
import oak.server.entity.ChannelParticipant;
import oak.server.entity.ChannelReadState;
import oak.server.entity.ChannelType;
import oak.server.entity.Message;
import oak.server.entity.Permission;
import oak.server.entity.User;
import oak.server.repository.ChannelParticipantRepository;
import oak.server.repository.ChannelReadStateRepository;
import oak.server.repository.ChannelRepository;
import oak.server.repository.MessageRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-(user, channel) read state: the {@code lastReadMessageId} pointer that backs unread/mention
 * counts. {@code ack} advances the pointer (the client sends it when a channel is opened or left);
 * {@code snapshot} returns the unread/mention count for every channel the user can read. On
 * registration, {@link #seedInitialReadStates} marks the existing archive as read so a new member
 * does not see the whole backlog as unread (Discord-faithful).
 *
 * <p><b>Performance note:</b> mention-count parses each unread message's mentions (cap 200 freshest
 * per channel). For a message whose author is a lazy proxy, parsing touches {@code author.roles}
 * and the channel's overrides — acceptable N+1 for a small single-tenant instance; revisit if
 * instances grow. {@code unreadCount} itself is a single SQL {@code count}, exact.
 */
@Service
@RequiredArgsConstructor
public class ReadStateService {

    private final ChannelReadStateRepository readStateRepository;
    private final ChannelRepository channelRepository;
    private final MessageRepository messageRepository;
    private final ChannelParticipantRepository participantRepository;
    private final AuthorizationService auth;
    private final MentionParser mentionParser;

    /** Advances the user's read pointer for {@code channelId}; {@code lastReadMessageId == null}
     *  means "mark everything currently in the channel as read" (uses the newest message id). */
    @Transactional
    public void ack(String username, Long channelId, Long lastReadMessageId) {
        User user = auth.requireUser(username);
        Channel channel = channelRepository.findById(channelId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found"));
        if (channel.getType() == ChannelType.DISCUSSION) {
            // An honest 400 beats a silently polluted read-state row (discussions are
            // read-state-silent by design — see the snapshot skip).
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Discussions are not read-state tracked");
        }
        auth.requireInChannel(user, channel, Permission.READ_MESSAGE_HISTORY);
        if (lastReadMessageId == null) {
            lastReadMessageId = messageRepository.findTopByChannelIdOrderByIdDesc(channelId)
                    .map(Message::getId).orElse(null);
        }
        ChannelReadState state = readStateRepository.findByUserIdAndChannelId(user.getId(), channelId)
                .orElseGet(() -> new ChannelReadState(user, channel, null));
        // Monotonic pointer: never move the read mark BACKWARDS. An ack computed from a partial
        // window (a client that jumped into the middle of history via search) must not rewind
        // the pointer and resurrect unread counts. Same-value writes fall through harmlessly.
        if (state.getLastReadMessageId() != null && lastReadMessageId != null
                && lastReadMessageId < state.getLastReadMessageId()) {
            return;
        }
        state.setLastReadMessageId(lastReadMessageId);
        readStateRepository.save(state);
    }

    /** Unread/mention counts for every channel the user may read; channels without READ access are
     *  omitted (a private channel is invisible, not "0 unread"). */
    @Transactional(readOnly = true)
    public List<ChannelReadStateResponse> snapshot(String username) {
        User user = auth.requireUser(username);
        List<ChannelReadStateResponse> out = new ArrayList<>();
        for (Channel channel : channelRepository.findAllByOrderByIdAsc()) {
            // DISCUSSION channels are read-state-silent: their unread must never leak into the
            // parent channel's counters (or the server-wide badge sums clients compute over
            // this snapshot) — the delegation branch would otherwise happily include them.
            // A CATEGORY carries no messages at all — same silence.
            if (channel.getType() == ChannelType.DISCUSSION || channel.getType() == ChannelType.CATEGORY) {
                continue;
            }
            if (!auth.hasInChannel(user, channel, Permission.READ_MESSAGE_HISTORY)) {
                continue;
            }
            Long lastRead = readStateRepository.findByUserIdAndChannelId(user.getId(), channel.getId())
                    .map(ChannelReadState::getLastReadMessageId).orElse(null);
            long readFloor = lastRead == null ? 0L : lastRead;
            long unreadCount = messageRepository.countByChannelIdAndIdGreaterThan(channel.getId(), readFloor);
            Long lastMessageId = messageRepository.findTopByChannelIdOrderByIdDesc(channel.getId())
                    .map(Message::getId).orElse(null);
            long mentionCount = unreadCount == 0 ? 0
                    : countMentionsOf(user, channel, readFloor);
            out.add(new ChannelReadStateResponse(
                    channel.getId(), unreadCount, mentionCount, lastRead, lastMessageId));
        }
        return out;
    }

    /** Seeds a read pointer for every existing channel set to the channel's current latest message,
     *  so a freshly-registered member's archive is not "unread". Called from {@code AuthService.register}.
     *  Empty channels seed {@code lastReadMessageId = null} (nothing unread, nothing read). */
    @Transactional
    public void seedInitialReadStates(User user) {
        for (Channel channel : channelRepository.findAllByOrderByIdAsc()) {
            // A fresh member has no DM channels, but skip them defensively — DM read-state is created on
            // demand (the participant accrues unread from their first message), never seeded here.
            // DISCUSSION channels are read-state-silent (see snapshot); a CATEGORY carries no messages.
            if (channel.getType() == ChannelType.DM || channel.getType() == ChannelType.GROUP_DM
                    || channel.getType() == ChannelType.DISCUSSION || channel.getType() == ChannelType.CATEGORY) {
                continue;
            }
            Long lastMessageId = messageRepository.findTopByChannelIdOrderByIdDesc(channel.getId())
                    .map(Message::getId).orElse(null);
            readStateRepository.save(new ChannelReadState(user, channel, lastMessageId));
        }
    }

    /** Number of unread messages (freshest 200) that @mention {@code user}. Own messages are skipped
     *  (you never @mention yourself into "unread"). In a DM / group DM, a mention can only reach the
     *  channel's participants, so the expanded id set is intersected with the participant roster — this
     *  bounds {@code @everyone}/{@code @here}/{@code @role} to the conversation and keeps the count
     *  correct if a future consumer routes mentions without the membership gate. */
    private long countMentionsOf(User user, Channel channel, long readFloor) {
        List<Message> unreadWindow = messageRepository
                .findTop200ByChannelIdAndIdGreaterThanOrderByIdDesc(channel.getId(), readFloor);
        Set<Long> participantIds = isDm(channel) ? participantIdsOf(channel) : null;
        Map<Long, Message> replyOriginals = replyOriginalsOf(unreadWindow);
        long count = 0;
        for (Message message : unreadWindow) {
            if (message.getAuthor() == null
                    || (message.getAuthor().getId() != null && message.getAuthor().getId().equals(user.getId()))) {
                continue;
            }
            List<MentionResponse> mentions = mentionParser.parse(
                    message.getContent(), message.getAuthor(), channel);
            Set<Long> pinged = mentionParser.expandToUserIds(mentions);
            if (participantIds != null) {
                pinged.retainAll(participantIds);
            }
            // A message counts ONCE even when it both @mentions the user and is a reply to their
            // message (mirrors MessageService.withReplyMention's dedup).
            if (pinged.contains(user.getId()) || replyPings(message, replyOriginals, user)) {
                count++;
            }
        }
        return count;
    }

    /** Reply ping, mirror of {@code MessageService.withReplyMention}: an unread reply to the
     *  user's own message counts as a mention even without an {@code @token} in the text. Keeps
     *  this pull-based counter in sync with the mentions list clients receive in MessageResponse
     *  — touch BOTH when changing the rule. No participant intersect needed: in a DM/group-DM the
     *  original's author is either a participant or no longer readable here at all. */
    private static boolean replyPings(Message message, Map<Long, Message> replyOriginals, User user) {
        Long repliedToId = message.getRepliedToId();
        if (repliedToId == null) {
            return false;
        }
        Message original = replyOriginals.get(repliedToId);
        return original != null
                && original.getAuthor() != null
                && original.getAuthor().getId() != null
                && original.getAuthor().getId().equals(user.getId());
    }

    /** Batched originals behind the window's replies (one query). */
    private Map<Long, Message> replyOriginalsOf(List<Message> messages) {
        Set<Long> ids = new HashSet<>();
        for (Message message : messages) {
            if (message.getRepliedToId() != null) {
                ids.add(message.getRepliedToId());
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, Message> out = new HashMap<>();
        for (Message original : messageRepository.findAllById(ids)) {
            out.put(original.getId(), original);
        }
        return out;
    }

    private static boolean isDm(Channel channel) {
        return channel != null
                && (channel.getType() == ChannelType.DM || channel.getType() == ChannelType.GROUP_DM);
    }

    private Set<Long> participantIdsOf(Channel channel) {
        Set<Long> ids = new HashSet<>();
        for (ChannelParticipant participant : participantRepository.findByChannelId(channel.getId())) {
            if (participant.getUser() != null && participant.getUser().getId() != null) {
                ids.add(participant.getUser().getId());
            }
        }
        return ids;
    }
}
