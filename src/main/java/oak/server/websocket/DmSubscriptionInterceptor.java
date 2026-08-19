package oak.server.websocket;

import lombok.RequiredArgsConstructor;
import oak.server.entity.Channel;
import oak.server.entity.ChannelType;
import oak.server.repository.ChannelParticipantRepository;
import oak.server.repository.ChannelRepository;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Closes the DM subscription leak. The simple broker does not authorize {@code SUBSCRIBE} frames, so
 * without this any authenticated member could subscribe to {@code /topic/channel.{dmId}} (enumerating
 * channel ids) and read DMs they are not part of. For a {@code SUBSCRIBE} whose destination is a DM or
 * group-DM channel's topic, this interceptor allows the subscription only for a
 * {@link ChannelParticipant}; otherwise it drops the frame (returns {@code null}), so the broker never
 * creates the subscription and the would-be eavesdropper receives nothing.
 *
 * <p>Public {@code TEXT}/{@code VOICE} channel subscriptions and all other frames pass through
 * untouched. The authenticated principal (set on {@code CONNECT} by {@code AuthChannelInterceptor}) is
 * a bare username string; membership is therefore resolved by username via
 * {@link ChannelParticipantRepository#isParticipant}.
 *
 * <p>The pattern covers the channel's whole topic family — the base message topic plus the
 * {@code .edited} and {@code .deleted} variants pushed by {@code MessageService}. A new
 * per-channel topic MUST be added here as well, or it is world-readable by id enumeration
 * (the simple broker does not authorize SUBSCRIBE frames).
 */
@Component
@RequiredArgsConstructor
public class DmSubscriptionInterceptor implements ChannelInterceptor {

    private static final Pattern CHANNEL_TOPIC =
            Pattern.compile("^/topic/channel\\.(\\d+)(?:\\.edited|\\.deleted)?$");

    private final ChannelRepository channelRepository;
    private final ChannelParticipantRepository participantRepository;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || !StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            return message;
        }
        String destination = accessor.getDestination();
        if (destination == null) {
            return message;
        }
        Matcher matcher = CHANNEL_TOPIC.matcher(destination);
        if (!matcher.matches()) {
            return message;   // not a per-channel message topic — public topics are unrestricted
        }
        Long channelId = Long.valueOf(matcher.group(1));
        Channel target = channelRepository.findById(channelId).orElse(null);
        if (target == null) {
            return message;   // unknown channel — let it through; nothing will be published anyway
        }
        if (target.getType() != ChannelType.DM && target.getType() != ChannelType.GROUP_DM) {
            return message;   // public channel: RBAC governs access elsewhere, the topic stays open
        }
        Principal principal = accessor.getUser();
        String username = principal == null ? null : principal.getName();
        if (username == null || !participantRepository.isParticipant(channelId, username)) {
            return null;      // drop the SUBSCRIBE — non-participant must not receive DM traffic
        }
        return message;
    }
}
