package oak.server.service;

import lombok.RequiredArgsConstructor;
import oak.server.dto.CreateMessageRequest;
import oak.server.dto.MessageResponse;
import oak.server.entity.Channel;
import oak.server.entity.Message;
import oak.server.entity.Permission;
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

import java.util.ArrayList;
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
    private final AttachmentUrlSigner attachmentUrlSigner;
    private final AuthorizationService auth;

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
        Message message = messageRepository.save(
                new Message(channel, author, content, attachmentKey, request.attachmentFileName()));
        MessageResponse response = toResponse(message);
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
        return messages.stream().map(this::toResponse).toList();
    }

    /** Builds the response view, signing a fresh GET URL when the message has an attachment. */
    private MessageResponse toResponse(Message message) {
        String attachmentKey = message.getAttachmentKey();
        String attachmentUrl = isBlank(attachmentKey) ? null : attachmentUrlSigner.presignedGetUrl(attachmentKey);
        return new MessageResponse(
                message.getId(),
                message.getChannel().getId(),
                message.getAuthor().getId(),
                message.getAuthor().getUsername(),
                message.getContent(),
                message.getCreatedAt(),
                message.getAttachmentFileName(),
                attachmentUrl);
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
