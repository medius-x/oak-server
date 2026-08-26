package oak.server.service;

import lombok.RequiredArgsConstructor;
import oak.server.dto.ChannelMyPermissions;
import oak.server.dto.ChannelResponse;
import oak.server.dto.CreateChannelRequest;
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
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

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
    private final SimpMessagingTemplate messagingTemplate;
    private final AuthorizationService auth;

    /**
     * The channel list AS SEEN BY ONE VIEWER: channels the member cannot VIEW (hidden) are omitted
     * entirely, and channels they can see but not use carry {@code locked=true} (no history read for
     * text / no connect for voice — the client's lock badge). DM / group-DM channels never appear
     * here: their access is membership-based (the DM branch in
     * {@code AuthorizationService.hasInChannel}), surfaced separately via the DM-channel endpoints.
     */
    @Transactional(readOnly = true)
    public List<ChannelResponse> list(String viewerUsername) {
        User viewer = auth.requireUser(viewerUsername);
        List<Channel> channels = channelRepository.findByTypeNotInOrderByIdAsc(
                List.of(ChannelType.DM, ChannelType.GROUP_DM));
        List<ChannelResponse> visible = new ArrayList<>(channels.size());
        for (Channel channel : channels) {
            // One mask per channel covers both the visibility filter and the locked flag (the
            // ReadStateService.snapshot precedent — a per-channel override load either way).
            long mask = auth.resolveChannelMask(viewer, channel);
            if (!Permissions.has(mask, Permission.VIEW_CHANNEL)) {
                continue;
            }
            boolean locked = channel.getType() == ChannelType.VOICE
                    ? !Permissions.has(mask, Permission.CONNECT)
                    : !Permissions.has(mask, Permission.READ_MESSAGE_HISTORY);
            visible.add(ChannelResponse.from(channel, locked));
        }
        return visible;
    }

    @Transactional
    public ChannelResponse create(CreateChannelRequest request, String actorUsername) {
        // Only members with MANAGE_CHANNELS may create channels (the OWNER/ADMIN short-circuit applies).
        auth.requireServer(auth.requireUser(actorUsername), Permission.MANAGE_CHANNELS);
        ChannelType type = request.type() != null ? request.type() : ChannelType.TEXT;
        Channel channel = channelRepository.save(new Channel(request.name(), type));
        ChannelResponse response = ChannelResponse.from(channel);
        // Broadcast so other connected clients add the channel to their sidebar live, without
        // having to reselect the server. Same REST-write → push pattern as MessageService.create.
        messagingTemplate.convertAndSend(CHANNELS_TOPIC, response);
        return response;
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
     *  the deleted id so every connected client drops it from their sidebar. Requires
     *  {@code MANAGE_CHANNELS} (OWNER/ADMINISTRATOR short-circuit applies). */
    @Transactional
    public void delete(Long channelId, String actorUsername) {
        auth.requireServer(auth.requireUser(actorUsername), Permission.MANAGE_CHANNELS);
        Channel channel = channelRepository.findById(channelId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found"));
        // The FKs messages.channel_id, channel_role_overrides.channel_id, channel_participants.channel_id,
        // and channel_read_states.channel_id have no ON DELETE CASCADE (ddl-auto=update never adds
        // cascade), so the children must go first or the channel DELETE fails with a FK violation.
        // participants / read-states are only present on DM channels, but cleaning them unconditionally
        // is a cheap no-op for public channels and keeps the admin DELETE path safe on a DM channel.
        messageRepository.deleteByChannelId(channelId);
        channelRoleOverrideRepository.deleteByChannelId(channelId);
        channelUserOverrideRepository.deleteByChannelId(channelId);
        channelParticipantRepository.deleteByChannelId(channelId);
        channelReadStateRepository.deleteByChannelId(channelId);
        channelRepository.delete(channel);
        messagingTemplate.convertAndSend(CHANNELS_DELETED_TOPIC, channelId);
    }
}
