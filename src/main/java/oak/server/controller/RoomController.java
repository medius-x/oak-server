package oak.server.controller;

import lombok.RequiredArgsConstructor;
import oak.server.config.JanusProperties;
import oak.server.dto.RoomResponse;
import oak.server.entity.Channel;
import oak.server.repository.ChannelRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Hands the client the Janus room info for a voice channel: the REST endpoint URL
 * (from {@code oak.janus.*}) and the room ID (= channel ID). The client then talks to
 * Janus directly. JWT-protected like the rest of the API.
 */
@RestController
@RequestMapping("/api/channels")
@RequiredArgsConstructor
public class RoomController {

    private final ChannelRepository channelRepository;
    private final JanusProperties janusProperties;

    @GetMapping("/{channelId}/room")
    public RoomResponse room(@PathVariable Long channelId) {
        Channel channel = channelRepository.findById(channelId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found"));
        return new RoomResponse(janusProperties.restUrl(), channel.getId());
    }
}
