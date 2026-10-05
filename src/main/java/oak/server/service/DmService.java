package oak.server.service;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import oak.server.dto.DmChannelEvent;
import oak.server.dto.DmChannelResponse;
import oak.server.dto.UserSummary;
import oak.server.entity.Channel;
import oak.server.entity.ChannelParticipant;
import oak.server.entity.ChannelType;
import oak.server.entity.Message;
import oak.server.entity.User;
import oak.server.repository.ChannelParticipantRepository;
import oak.server.repository.ChannelReadStateRepository;
import oak.server.repository.ChannelRepository;
import oak.server.repository.ChannelRoleOverrideRepository;
import oak.server.repository.MessageRepository;
import oak.server.repository.RelationshipRepository;
import oak.server.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Direct-message channels: list, get-or-create a 1:1 DM, and create / mutate group DMs. A DM is a
 * {@link Channel} of type {@code DM}/{@code GROUP_DM} plus {@link ChannelParticipant} rows; access to
 * content is membership-based (the DM branch in {@code AuthorizationService.hasInChannel}), so messages,
 * read-state, and attachments flow through the existing endpoints and services. This service owns only
 * DM <i>lifecycle</i>: who may create / join / leave / rename / close.
 *
 * <p><b>1:1 deduplication:</b> each 1:1 DM carries a deterministic {@code dmKey} ({@code min_max} of the
 * two user ids) under a unique constraint, so re-opening a DM returns the same channel. Two concurrent
 * "DM the same user" requests collapse to a unique-constraint violation on the second insert; the loser
 * clears its persistence context and re-reads the winner (the transaction is kept alive with
 * {@code noRollbackFor = DataIntegrityViolationException}, mirroring the refresh-token reuse pattern).
 *
 * <p><b>Group DM ownership:</b> the creator is the {@code owner} (nullable {@link Channel#getOwner()}).
 * Only the owner may add/remove other participants or close the group; any participant may rename it or
 * leave. When the owner leaves, ownership transfers to the earliest-joined remaining participant.
 *
 * <p><b>Blocking:</b> a DM / group invite cannot involve a user who blocks, or is blocked by, the actor.
 *
 * <p><b>DM restriction:</b> a member holding a role with {@code RESTRICT_PRIVATE_MESSAGES} (see
 * {@code AuthorizationService.dmRestricted}) can neither start nor receive DMs and cannot be added
 * to a group; existing conversations stay readable (the send-side gate for those lives in
 * {@code MessageService} via {@code requireDmSendAllowed}).
 *
 * <p><b>Events:</b> each mutation publishes a {@link DmChannelEvent} to every participant on
 * {@code /user/queue/dm.channels} via {@code convertAndSendToUser(username, ...)}.
 */
@Service
@RequiredArgsConstructor
public class DmService {

    /** Per-user queue DM-channel deltas are pushed to (clients subscribe to {@code /user/queue/dm.channels}). */
    public static final String DM_CHANNELS_QUEUE = "/queue/dm.channels";

    /** Group-DM participant limits (caller + others). */
    private static final int GROUP_MIN = 3;
    private static final int GROUP_MAX = 10;

    private final ChannelRepository channelRepository;
    private final ChannelParticipantRepository participantRepository;
    private final ChannelReadStateRepository channelReadStateRepository;
    private final ChannelRoleOverrideRepository channelRoleOverrideRepository;
    private final MessageRepository messageRepository;
    private final oak.server.repository.MessageReactionRepository reactionRepository;
    private final UserRepository userRepository;
    private final RelationshipRepository relationshipRepository;
    private final AuthorizationService auth;
    private final AttachmentUrlSigner signer;
    private final SimpMessagingTemplate messagingTemplate;
    private final EntityManager entityManager;

    @Transactional(readOnly = true)
    public List<DmChannelResponse> listMine(String username) {
        User me = auth.requireUser(username);
        List<Long> channelIds = participantRepository.findChannelIdsByUserId(me.getId());
        if (channelIds.isEmpty()) {
            return List.of();
        }
        List<DmChannelResponse> out = new ArrayList<>();
        for (Channel channel : channelRepository.findAllById(channelIds)) {
            // Discussions track membership through the SAME channel_participants
            // rows — without the type gate every tracked thread leaked into the
            // DM list, and the client rendered it as a DM with ITSELF (the only
            // participant row is the tracker; live report 2026-09-24).
            if (channel.getType() != ChannelType.DM && channel.getType() != ChannelType.GROUP_DM) {
                continue;
            }
            out.add(toResponse(channel));
        }
        // Newest activity first; channels with no messages sink to the bottom (stable by id).
        out.sort(Comparator
                .comparing(DmChannelResponse::lastMessageAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(DmChannelResponse::id, Comparator.reverseOrder()));
        return out;
    }

    /** Get-or-create a 1:1 DM with {@code recipientId}. Returns the existing channel if one exists. */
    @Transactional(noRollbackFor = DataIntegrityViolationException.class)
    public DmChannelResponse getOrCreateDm(String username, Long recipientId) {
        User me = auth.requireUser(username);
        if (me.getId().equals(recipientId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You cannot start a DM with yourself");
        }
        User recipient = userRepository.findById(recipientId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (relationshipRepository.blockedEitherDirection(me, recipient)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot message a blocked user");
        }
        if (auth.dmRestricted(me)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You are not allowed to send private messages");
        }
        if (auth.dmRestricted(recipient)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This member cannot receive private messages");
        }
        String key = dmKey(me.getId(), recipient.getId());
        Optional<Channel> existing = channelRepository.findByDmKey(key);
        if (existing.isPresent()) {
            return toResponse(existing.get());
        }
        Channel dm = new Channel("", ChannelType.DM);
        dm.setDmKey(key);
        try {
            channelRepository.saveAndFlush(dm);
        } catch (DataIntegrityViolationException race) {
            // Lost the concurrent-create race: the other request inserted the channel first. Clear the
            // persistence context (it still holds the failed insert) before re-reading the winner.
            entityManager.clear();
            Channel winner = channelRepository.findByDmKey(key).orElseThrow(() -> race);
            return toResponse(winner);
        }
        participantRepository.save(new ChannelParticipant(dm, me));
        participantRepository.save(new ChannelParticipant(dm, recipient));
        publish("CREATED", dm);
        return toResponse(dm);
    }

    /** Create a group DM. The caller is the owner; {@code participantIds} are the other members. */
    @Transactional
    public DmChannelResponse createGroupDm(String username, List<Long> participantIds, String name) {
        User me = auth.requireUser(username);
        if (auth.dmRestricted(me)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You are not allowed to send private messages");
        }
        List<User> others = resolveOthers(me, participantIds);
        int total = 1 + others.size();
        if (total < GROUP_MIN) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A group DM needs at least " + GROUP_MIN + " participants");
        }
        if (total > GROUP_MAX) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A group DM can have at most " + GROUP_MAX + " participants");
        }
        Channel group = new Channel(name == null || name.isBlank() ? "" : name.trim(), ChannelType.GROUP_DM);
        group.setOwner(me);
        channelRepository.save(group);
        participantRepository.save(new ChannelParticipant(group, me));
        for (User other : others) {
            participantRepository.save(new ChannelParticipant(group, other));
        }
        publish("CREATED", group);
        return toResponse(group);
    }

    /** Add a participant to a group DM (owner-only). */
    @Transactional
    public DmChannelResponse addParticipant(String username, Long channelId, Long userId) {
        User me = auth.requireUser(username);
        Channel group = requireGroupDm(channelId);
        requireOwner(group, me);
        User toAdd = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (relationshipRepository.blockedEitherDirection(me, toAdd)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot add a blocked user");
        }
        if (auth.dmRestricted(me)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You are not allowed to send private messages");
        }
        if (auth.dmRestricted(toAdd)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This member cannot receive private messages");
        }
        if (participantRepository.existsByChannelIdAndUserId(channelId, userId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Already a participant");
        }
        if (participantRepository.findByChannelId(channelId).size() >= GROUP_MAX) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This group DM is full");
        }
        participantRepository.save(new ChannelParticipant(group, toAdd));
        publish("PARTICIPANT_ADDED", group);
        return toResponse(group);
    }

    /**
     * Remove a participant, or leave. The owner may remove anyone; a non-owner may only remove themselves
     * (= leave). If the owner leaves, ownership transfers to the earliest-joined remaining member; if the
     * last member leaves, the empty group is deleted.
     */
    @Transactional
    public void removeParticipant(String username, Long channelId, Long userId) {
        User me = auth.requireUser(username);
        Channel group = requireGroupDm(channelId);
        boolean leavingSelf = me.getId().equals(userId);
        if (leavingSelf) {
            requireParticipant(group, me);
        } else {
            requireOwner(group, me);
        }
        ChannelParticipant row = participantRepository.findByChannelIdAndUserId(channelId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not a participant"));
        participantRepository.delete(row);
        participantRepository.flush();
        List<ChannelParticipant> remaining = participantRepository.findByChannelId(channelId);
        if (remaining.isEmpty()) {
            deleteChannelCompletely(group);   // last member left → nothing to broadcast to
            return;
        }
        boolean ownerLeft = group.getOwner() != null && group.getOwner().getId().equals(userId);
        if (ownerLeft) {
            group.setOwner(earliestJoined(remaining));
            channelRepository.save(group);
        }
        publish("PARTICIPANT_REMOVED", group);
    }

    /** Rename a group DM (any participant). */
    @Transactional
    public DmChannelResponse rename(String username, Long channelId, String name) {
        User me = auth.requireUser(username);
        Channel group = requireGroupDm(channelId);
        requireParticipant(group, me);
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name must not be empty");
        }
        group.setName(name.trim());
        channelRepository.save(group);
        publish("RENAMED", group);
        return toResponse(group);
    }

    /** Close a DM. A 1:1 DM may be closed by either participant; a group DM only by its owner. */
    @Transactional
    public void close(String username, Long channelId) {
        User me = auth.requireUser(username);
        Channel channel = channelRepository.findById(channelId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found"));
        if (channel.getType() != ChannelType.DM && channel.getType() != ChannelType.GROUP_DM) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a DM channel");
        }
        requireParticipant(channel, me);
        if (channel.getType() == ChannelType.GROUP_DM && !isOwner(channel, me)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the owner can close this group DM");
        }
        publish("CLOSED", channel);   // notify participants before the channel (and its rows) are gone
        deleteChannelCompletely(channel);
    }

    // --- helpers ---

    /** Resolves the other participants (de-duped, self removed), rejecting any blocked either-way. */
    private List<User> resolveOthers(User me, List<Long> participantIds) {
        if (participantIds == null) {
            return List.of();
        }
        Set<Long> unique = new LinkedHashSet<>(participantIds);
        unique.remove(me.getId());
        List<User> others = new ArrayList<>();
        for (Long id : unique) {
            User other = userRepository.findById(id)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + id));
            if (relationshipRepository.blockedEitherDirection(me, other)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot add a blocked user");
            }
            if (auth.dmRestricted(other)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This member cannot receive private messages");
            }
            others.add(other);
        }
        return others;
    }

    private DmChannelResponse toResponse(Channel channel) {
        List<UserSummary> participants = participantRepository.findByChannelId(channel.getId()).stream()
                .map(participant -> UserSummary.from(participant.getUser(), signer))
                .sorted(Comparator.comparing(UserSummary::username))
                .toList();
        Message last = messageRepository.findTopByChannelIdOrderByIdDesc(channel.getId()).orElse(null);
        return DmChannelResponse.from(channel, participants, last);
    }

    private void publish(String type, Channel channel) {
        DmChannelResponse snapshot = toResponse(channel);
        DmChannelEvent event = new DmChannelEvent(type, snapshot.id(), snapshot.type(),
                snapshot.name(), snapshot.ownerId(), snapshot.participants());
        for (UserSummary participant : snapshot.participants()) {
            messagingTemplate.convertAndSendToUser(participant.username(), DM_CHANNELS_QUEUE, event);
        }
    }

    /** Deletes a DM channel and everything tied to it — the FKs have no ON DELETE CASCADE under ddl-auto. */
    private void deleteChannelCompletely(Channel channel) {
        // Reactions FIRST: message_reactions.message_id has no ON DELETE CASCADE, so a bulk
        // message delete on a channel holding any reacted message would fail with a FK violation.
        reactionRepository.deleteByChannelId(channel.getId());
        messageRepository.deleteByChannelId(channel.getId());
        channelRoleOverrideRepository.deleteByChannelId(channel.getId());
        participantRepository.deleteByChannelId(channel.getId());
        channelReadStateRepository.deleteByChannelId(channel.getId());
        channelRepository.delete(channel);
    }

    private Channel requireGroupDm(Long channelId) {
        Channel channel = channelRepository.findById(channelId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found"));
        if (channel.getType() != ChannelType.GROUP_DM) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a group DM");
        }
        return channel;
    }

    private void requireParticipant(Channel channel, User user) {
        if (!participantRepository.existsByChannelIdAndUserId(channel.getId(), user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a participant of this DM");
        }
    }

    private void requireOwner(Channel channel, User user) {
        if (!isOwner(channel, user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the group DM owner can do that");
        }
    }

    private boolean isOwner(Channel channel, User user) {
        return channel.getOwner() != null && channel.getOwner().getId().equals(user.getId());
    }

    private User earliestJoined(List<ChannelParticipant> participants) {
        return participants.stream()
                .min(Comparator.comparing(ChannelParticipant::getJoinedAt))
                .map(ChannelParticipant::getUser)
                .orElse(null);
    }

    private static String dmKey(Long firstId, Long secondId) {
        long min = Math.min(firstId, secondId);
        long max = Math.max(firstId, secondId);
        return min + "_" + max;
    }
}
