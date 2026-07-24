package oak.server.service;

import lombok.RequiredArgsConstructor;
import oak.server.dto.ChannelResponse;
import oak.server.dto.CreateChannelRequest;
import oak.server.entity.Channel;
import oak.server.entity.ChannelType;
import oak.server.entity.Permission;
import oak.server.repository.ChannelRepository;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ChannelService {

    /** STOMP topic broadcasting a channel-list change (a newly created channel) to every connected
     *  client. Plural ({@code channels}) to stay distinct from {@code /topic/channel.{id}}, which
     *  carries a single channel's live messages. */
    public static final String CHANNELS_TOPIC = "/topic/channels";

    private final ChannelRepository channelRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final AuthorizationService auth;

    @Transactional(readOnly = true)
    public List<ChannelResponse> list() {
        return channelRepository.findAllByOrderByIdAsc().stream()
                .map(ChannelResponse::from)
                .toList();
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
}
