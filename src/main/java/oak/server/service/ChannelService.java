package oak.server.service;

import lombok.RequiredArgsConstructor;
import oak.server.dto.ChannelResponse;
import oak.server.dto.CreateChannelRequest;
import oak.server.entity.Channel;
import oak.server.entity.ChannelType;
import oak.server.repository.ChannelRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ChannelService {

    private final ChannelRepository channelRepository;

    @Transactional(readOnly = true)
    public List<ChannelResponse> list() {
        return channelRepository.findAllByOrderByIdAsc().stream()
                .map(ChannelResponse::from)
                .toList();
    }

    @Transactional
    public ChannelResponse create(CreateChannelRequest request) {
        ChannelType type = request.type() != null ? request.type() : ChannelType.TEXT;
        Channel channel = channelRepository.save(new Channel(request.name(), type));
        return ChannelResponse.from(channel);
    }
}
