package oak.server.service;

import lombok.RequiredArgsConstructor;
import oak.server.dto.ChannelMyPermissions;
import oak.server.dto.ChannelOrderEntry;
import oak.server.dto.ChannelResponse;
import oak.server.dto.CreateChannelRequest;
import oak.server.dto.ReorderChannelsRequest;
import oak.server.entity.Channel;
import oak.server.entity.ChannelType;
import oak.server.entity.Permission;
import oak.server.entity.Permissions;
import oak.server.entity.User;
import oak.server.repository.ChannelParticipantRepository;
import oak.server.repository.ChannelReadStateRepository;
import oak.server.repository.ChannelRepository;
import oak.server.repository.ChannelRoleOverrideRepository;
import oak.server.repository.ChannelUserOverrideRepository;
import oak.server.repository.MessageRepository;
import oak.server.websocket.VoiceAccessEnforcer;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ChannelService {

    /** STOMP topic broadcasting a channel-list change (a newly created channel) to every connected
     *  client. Plural ({@code channels}) to stay distinct from {@code /topic/channel.{id}}, which
     *  carries a single channel's live messages. */
    public static final String CHANNELS_TOPIC = "/topic/channels";

    /** STOMP topic broadcasting a channel <i>deletion</i> (the deleted channel's id) to every connected
     *  client, so each removes it from its sidebar live. Separate from {@link #CHANNELS_TOPIC} (which
     *  carries a {@link ChannelResponse} for a newly created channel) — reusing it would make a delete
     *  indistinguishable from a create on the client. */
    public static final String CHANNELS_DELETED_TOPIC = "/topic/channels.deleted";

    /** STOMP topic pinging clients that channel VISIBILITY changed (an override was written) so they
     *  refetch their viewer-filtered channel list. The body is a bare timestamp — no channel data is
     *  broadcast, so the ping leaks nothing to clients that must not learn which channel changed. */
    public static final String CHANNELS_CHANGED_TOPIC = "/topic/channels.changed";

    private final ChannelRepository channelRepository;
    private final MessageRepository messageRepository;
    private final ChannelRoleOverrideRepository channelRoleOverrideRepository;
    private final ChannelUserOverrideRepository channelUserOverrideRepository;
    private final ChannelParticipantRepository channelParticipantRepository;
    private final ChannelReadStateRepository channelReadStateRepository;
    private final oak.server.repository.MessageReactionRepository reactionRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final AuthorizationService auth;
    private final VoiceAccessEnforcer voiceEnforcer;
    private final JanusVoiceAccessService voiceAccess;
    private final DiscussionService discussionService;

    /**
     * The channel list AS SEEN BY ONE VIEWER: channels the member cannot VIEW (hidden) are omitted
     * entirely, and channels they can see but not use carry {@code locked=true} (no history read for
     * text / no connect for voice — the client's lock badge). DM / group-DM channels never appear
     * here: their access is membership-based (the DM branch in
     * {@code AuthorizationService.hasInChannel}), surfaced separately via the DM-channel endpoints.
     * The rows arrive in the manual sidebar order — {@code (position, id)}.
     */
    @Transactional(readOnly = true)
    public List<ChannelResponse> list(String viewerUsername) {
        User viewer = auth.requireUser(viewerUsername);
        // DISCUSSION channels never appear in the sidebar's channel list — they surface through
        // the discussion endpoints (a tile under the origin message / the tracked list).
        List<Channel> channels = channelRepository.findByTypeNotInOrderByPositionAscIdAsc(
                List.of(ChannelType.DM, ChannelType.GROUP_DM, ChannelType.DISCUSSION));
        List<ChannelResponse> visible = new ArrayList<>(channels.size());
        for (Channel channel : channels) {
            // A CATEGORY is a pure container — it is always visible (the mask machinery has
            // nothing to resolve on it) and never locked; the client hides a category whose
            // children are all filtered out for this viewer.
            if (channel.getType() == ChannelType.CATEGORY) {
                visible.add(ChannelResponse.from(channel, false));
                continue;
            }
            // One mask per channel covers both the visibility filter and the locked flag (the
            // ReadStateService.snapshot precedent — a per-channel override load either way).
            long mask = auth.resolveChannelMask(viewer, channel);
            if (!Permissions.has(mask, Permission.VIEW_CHANNEL)) {
                continue;
            }
            visible.add(ChannelResponse.from(channel, lockedFor(channel, mask)));
        }
        return visible;
    }

    /** The viewer's lock flag: may SEE the channel but not USE it (no READ for TEXT / no CONNECT for
     *  VOICE). Derived from a mask already resolved for that exact viewer. */
    private static boolean lockedFor(Channel channel, long viewerMask) {
        return channel.getType() == ChannelType.VOICE
                ? !Permissions.has(viewerMask, Permission.CONNECT)
                : !Permissions.has(viewerMask, Permission.READ_MESSAGE_HISTORY);
    }

    /** Renames a TEXT/VOICE channel. {@code MANAGE_CHANNELS}, consistent with create/delete (all
     *  server-scoped today). DM ids are rejected — their rename belongs to the participants
     *  ({@code PATCH /api/channels/{id}}), and a 1:1 DM has no editable name at all. Broadcasts the
     *  bare {@link #CHANNELS_CHANGED_TOPIC} ping: clients refetch their viewer-filtered list, and a
     *  broadcast {@link ChannelResponse} could not carry the per-viewer {@code locked} flag. The
     *  response DOES carry the actor's own {@code locked} so an optimistic client update doesn't
     *  flash a locked channel unlocked before the ping refetch lands. */
    @Transactional
    public ChannelResponse rename(Long channelId, String name, String actorUsername) {
        User actor = auth.requireUser(actorUsername);
        auth.requireServer(actor, Permission.MANAGE_CHANNELS);
        Channel channel = channelRepository.findById(channelId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found"));
        if (channel.getType() == ChannelType.DM || channel.getType() == ChannelType.GROUP_DM) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a server channel");
        }
        if (channel.getType() == ChannelType.DISCUSSION) {
            // A discussion has no name of its own (the origin snapshot IS its display), and the
            // mask resolution below would mis-resolve an override-less discussion channel.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a server channel");
        }
        channel.setName(name.trim());
        channel = channelRepository.save(channel);
        messagingTemplate.convertAndSend(CHANNELS_CHANGED_TOPIC, System.currentTimeMillis());
        // A renamed CATEGORY answers unlocked (the list's container rule).
        return ChannelResponse.from(channel, channel.getType() == ChannelType.CATEGORY
                ? false
                : lockedFor(channel, auth.resolveChannelMask(actor, channel)));
    }

    @Transactional
    public ChannelResponse create(CreateChannelRequest request, String actorUsername) {
        // Only members with MANAGE_CHANNELS may create channels (the OWNER/ADMIN short-circuit applies).
        auth.requireServer(auth.requireUser(actorUsername), Permission.MANAGE_CHANNELS);
        ChannelType type = request.type() != null ? request.type() : ChannelType.TEXT;
        Long parentId = validatedParentId(type, request.parentId());
        Channel channel = new Channel(request.name(), type);
        channel.setParentId(parentId);
        // New rows append to the END of the sidebar (max + 1) — the entity's Java default 0
        // must never leak to the wire (it would jump the channel to the top).
        Channel top = channelRepository.findTopByOrderByPositionDesc();
        channel.setPosition(top != null ? top.getPosition() + 1 : 0);
        channel = channelRepository.save(channel);
        ChannelResponse response = ChannelResponse.from(channel);
        // Broadcast so other connected clients add the channel to their sidebar live, without
        // having to reselect the server. Same REST-write → push pattern as MessageService.create.
        messagingTemplate.convertAndSend(CHANNELS_TOPIC, response);
        return response;
    }

    /** The create/reorder parent gate: null passes (top level); anything else must reference
     *  an existing CATEGORY, and a CATEGORY itself must not carry a parent. All failures are
     *  400s — the client's create dialog and drag-drop both surface the message verbatim. */
    private Long validatedParentId(ChannelType type, Long parentId) {
        if (parentId == null) {
            return null;
        }
        if (type == ChannelType.CATEGORY) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A category cannot have a parent");
        }
        Channel parent = channelRepository.findById(parentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown parent category"));
        if (parent.getType() != ChannelType.CATEGORY) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parent must be a category");
        }
        return parentId;
    }

    /**
     * The full-sidebar reorder (Discord's drag-drop persist): the payload lists EVERY
     * non-DM channel and category exactly once, in the intended sidebar order, each with
     * its (possibly new) parent category. Positions are renumbered densely by list index;
     * the parent change and the move land in ONE transaction. Clients refetch on the
     * {@link #CHANNELS_CHANGED_TOPIC} ping, which fires AFTER COMMIT — an in-tx ping would
     * let a subscriber's refetch beat the commit and read the old order (the invites lesson).
     */
    @Transactional
    public void reorder(ReorderChannelsRequest request, String actorUsername) {
        auth.requireServer(auth.requireUser(actorUsername), Permission.MANAGE_CHANNELS);
        List<Channel> reorderable = channelRepository.findByTypeNotInOrderByPositionAscIdAsc(
                List.of(ChannelType.DM, ChannelType.GROUP_DM, ChannelType.DISCUSSION));
        Map<Long, Channel> byId = new HashMap<>(reorderable.size() * 2);
        for (Channel channel : reorderable) {
            byId.put(channel.getId(), channel);
        }
        // Payload == the whole reorderable set, each id exactly once: the removes consume the
        // first occurrence of a duplicate, so a leftover null / a non-empty map both reject
        // unknown ids, DMs and duplicates with one message.
        List<Channel> ordered = new ArrayList<>(request.entries().size());
        for (ChannelOrderEntry entry : request.entries()) {
            Channel channel = byId.remove(entry.id());
            if (channel == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reorder must list every channel and category");
            }
            ordered.add(channel);
        }
        if (!byId.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reorder must list every channel and category");
        }
        // Parents resolve against the SAME loaded set — a parent is by definition a
        // reorderable category, so no second findById round trip is needed.
        for (int entryIndex = 0; entryIndex < request.entries().size(); entryIndex++) {
            ChannelOrderEntry entry = request.entries().get(entryIndex);
            Channel channel = ordered.get(entryIndex);
            if (entry.parentId() == null) {
                channel.setParentId(null);
                continue;
            }
            if (channel.getType() == ChannelType.CATEGORY) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A category cannot have a parent");
            }
            Channel parent = findByIdInSet(ordered, entry.parentId());
            if (parent == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown parent category");
            }
            if (parent.getType() != ChannelType.CATEGORY) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parent must be a category");
            }
            channel.setParentId(entry.parentId());
        }
        int position = 0;
        for (Channel channel : ordered) {
            channel.setPosition(position++);
        }
        channelRepository.saveAll(ordered);
        pingChannelsChangedAfterCommit();
    }

    private static Channel findByIdInSet(List<Channel> channels, Long channelId) {
        for (Channel channel : channels) {
            if (channel.getId().equals(channelId)) {
                return channel;
            }
        }
        return null;
    }

    /** The reorder's channels-changed ping, AFTER COMMIT (an in-tx ping lets subscribers
     *  refetch before the new order is visible — the invites lesson). No active
     *  synchronization (a no-tx caller) pings directly. */
    private void pingChannelsChangedAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            messagingTemplate.convertAndSend(CHANNELS_CHANGED_TOPIC, System.currentTimeMillis());
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                messagingTemplate.convertAndSend(CHANNELS_CHANGED_TOPIC, System.currentTimeMillis());
            }
        });
    }

    /** The member's resolved SEND_MESSAGES / ATTACH_FILES on one channel (override-aware) — for the
     *  client to gate the composer. Channel-scoped (unlike {@code /api/users/me/permissions}, which is
     *  server-wide), so private channels and per-channel denies are honoured. */
    @Transactional(readOnly = true)
    public ChannelMyPermissions myPermissions(Long channelId, String username) {
        Channel channel = channelRepository.findById(channelId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found"));
        User user = auth.requireUser(username);
        return new ChannelMyPermissions(
                auth.hasInChannel(user, channel, Permission.SEND_MESSAGES),
                auth.hasInChannel(user, channel, Permission.ATTACH_FILES));
    }

    /** Deletes a channel and everything tied to it (messages + per-channel overrides), then broadcasts
     *  the deleted id so every connected client drops it from their sidebar. Anyone sitting in the
     *  channel's voice room is force-evicted first ({@link VoiceAccessEnforcer} — registry + Janus +
     *  cooperative push): the {@code channels.deleted} broadcast only honest clients act on, and a
     *  rogue one would otherwise keep the ephemeral Janus room alive single-handedly. Requires
     *  {@code MANAGE_CHANNELS} (OWNER/ADMINISTRATOR short-circuit applies). */
    @Transactional
    public void delete(Long channelId, String actorUsername) {
        auth.requireServer(auth.requireUser(actorUsername), Permission.MANAGE_CHANNELS);
        Channel channel = channelRepository.findById(channelId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found"));
        // A non-empty category is refused (Discord parity): its children must be moved or
        // deleted first. Checked BEFORE the voice evictions — a 400 must not kick anyone.
        if (channel.getType() == ChannelType.CATEGORY && channelRepository.existsByParentId(channelId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Category is not empty — move or delete its channels first");
        }
        // Evict every live voice session BEFORE destroying the channel's data: the LEFT/KICKED pushes
        // then reference a channel that still exists for concurrent readers, and a later FK/rollback
        // failure of the purge at worst evicts members of a still-existing channel (they can rejoin —
        // self-healing). kickAll never queries the database, so it adds no rollback surface here.
        voiceEnforcer.kickAll(channelId, actorUsername);
        // Then tear down the gateway room itself: the registry sweep only reaches STOMP-visible
        // members, while a non-STOMP rogue would otherwise hold the ephemeral room alive until the
        // gateway restarts. Fire-and-forget on the SAME single-thread executor as the evictions —
        // submitted after them, so it runs after them.
        voiceAccess.destroyRoom(channelId);
        // The FKs messages.channel_id, channel_role_overrides.channel_id, channel_participants.channel_id,
        // and channel_read_states.channel_id have no ON DELETE CASCADE (ddl-auto=update never adds
        // cascade), so the children must go first or the channel DELETE fails with a FK violation.
        // Reactions go FIRST of all (message_reactions.message_id, same no-cascade story). The
        // channel's discussions (their channels/messages/participants/read-states/discussion rows)
        // are torn down through the shared DiscussionService helper — a discussion never outlives
        // its parent. participants / read-states are only present on DM channels, but cleaning them
        // unconditionally is a cheap no-op for public channels and keeps the admin DELETE path safe
        // on a DM channel.
        reactionRepository.deleteByChannelId(channelId);
        messageRepository.deleteByChannelId(channelId);
        channelRoleOverrideRepository.deleteByChannelId(channelId);
        channelUserOverrideRepository.deleteByChannelId(channelId);
        channelParticipantRepository.deleteByChannelId(channelId);
        channelReadStateRepository.deleteByChannelId(channelId);
        discussionService.deleteDiscussionsOf(channelId);
        channelRepository.delete(channel);
        messagingTemplate.convertAndSend(CHANNELS_DELETED_TOPIC, channelId);
    }
}
