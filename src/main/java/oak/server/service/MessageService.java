package oak.server.service;

import lombok.RequiredArgsConstructor;
import oak.server.dto.CreateMessageRequest;
import oak.server.dto.MessageResponse;
import oak.server.entity.Channel;
import oak.server.entity.Message;
import oak.server.entity.User;
import oak.server.repository.ChannelRepository;
import oak.server.repository.MessageRepository;
import oak.server.repository.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MessageService {

    /** STOMP destination a channel's new messages are pushed to: {@code /topic/channel.{id}}. */
    public static final String CHANNEL_TOPIC_PREFIX = "/topic/channel.";

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final MessageRepository messageRepository;
    private final ChannelRepository channelRepository;
    private final UserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;

    @Transactional
    public MessageResponse create(Long channelId, CreateMessageRequest request, String authorUsername) {
        Channel channel = channelRepository.findById(channelId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found"));
        User author = userRepository.findByUsername(authorUsername)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Author not found"));
        Message message = messageRepository.save(new Message(channel, author, request.content()));
        MessageResponse response = MessageResponse.from(message);
        // Variant A: written via REST, then broadcast to everyone subscribed to this channel.
        messagingTemplate.convertAndSend(CHANNEL_TOPIC_PREFIX + channelId, response);
        return response;
    }

    /** Returns the most recent {@code limit} messages of a channel, in chronological order. */
    @Transactional(readOnly = true)
    public List<MessageResponse> listByChannel(Long channelId, Integer limit) {
        if (!channelRepository.existsById(channelId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found");
        }
        List<Message> messages = messageRepository
                .findByChannelIdOrderByIdDesc(channelId, PageRequest.of(0, clamp(limit)))
                .getContent();
        Collections.reverse(messages);
        return messages.stream().map(MessageResponse::from).toList();
    }

    private static int clamp(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
