package oak.server.websocket;

import lombok.RequiredArgsConstructor;
import oak.server.entity.Channel;
import oak.server.entity.ChannelType;
import oak.server.entity.Permission;
import oak.server.entity.User;
import oak.server.repository.ChannelParticipantRepository;
import oak.server.repository.ChannelRepository;
import oak.server.service.AuthorizationService;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Closes the per-channel SUBSCRIPTION leak. The simple broker does not authorize
 * {@code SUBSCRIBE} frames, so without this any authenticated member could enumerate channel ids
 * and read topics they have no business receiving:
 * <ul>
 *   <li><b>DM / group-DM topics</b> — allowed only for a {@link ChannelParticipant} (a
 *       non-participant must not receive DM traffic, not even the instance OWNER).</li>
 *   <li><b>TEXT / VOICE topics</b> — allowed only with {@code READ_MESSAGE_HISTORY} in the
 *       channel (layered override resolution; a hidden channel fails via the VIEW_CHANNEL master
 *       gate). READ is the gate — not VIEW — because a LOCKED channel stays in the outsider's
 *       sidebar (visible) and clients subscribe to every listed channel: a VIEW-only gate would
 *       hand them live message bodies while the history REST endpoint returns 403.</li>
 * </ul>
 * Denied frames are dropped ({@code null}), so the broker never creates the subscription and the
 * would-be eavesdropper receives nothing.
 *
 * <p><b>Subscribe-time only:</b> an already-established subscription survives a later permission
 * flip until the client unsubscribes/reconnects. Well-behaved clients heal via the
 * {@code /topic/channels.changed} refetch ping (they drop hidden channels from their list and
 * unsubscribe); a hostile client keeps receiving until reconnect — the same accepted property the
 * DM design has always had.
 *
 * <p>The authenticated principal (set on {@code CONNECT} by {@code AuthChannelInterceptor}) is a
 * bare username string. The interceptor runs on the STOMP clientInboundChannel, which has no
 * Hibernate session — {@link AuthorizationService#findUser} eager-fetches roles for exactly that.
 *
 * <p>The pattern covers the channel's whole topic family — the base message topic plus the
 * {@code .edited}, {@code .deleted}, {@code .reactions} and {@code .discussions} variants
 * pushed by {@code MessageService} / {@code DiscussionService}. A DISCUSSION channel id in the
 * base family resolves through the PARENT channel (the delegation branch in
 * {@code AuthorizationService.hasInChannel}); the {@code .discussions} suffix lives on the
 * PARENT's id and is gated by the same READ check. A new per-channel topic MUST be added here
 * as well, or it is world-readable by id enumeration (the simple broker does not authorize
 * SUBSCRIBE frames).
 */
@Component
@RequiredArgsConstructor
public class ChannelSubscriptionInterceptor implements ChannelInterceptor {

    private static final Pattern CHANNEL_TOPIC =
            Pattern.compile("^/topic/channel\\.(\\d+)(?:\\.edited|\\.deleted|\\.reactions|\\.discussions)?$");

    private final ChannelRepository channelRepository;
    private final ChannelParticipantRepository participantRepository;
    private final AuthorizationService auth;

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
        Principal principal = accessor.getUser();
        String username = principal == null ? null : principal.getName();
        if (target.getType() == ChannelType.DM || target.getType() == ChannelType.GROUP_DM) {
            if (username == null || !participantRepository.isParticipant(channelId, username)) {
                return null;  // drop the SUBSCRIBE — non-participant must not receive DM traffic
            }
            return message;
        }
        Optional<User> subscriber = username == null ? Optional.empty() : auth.findUser(username);
        if (subscriber.isEmpty()
                || !auth.hasInChannel(subscriber.get(), target, Permission.READ_MESSAGE_HISTORY)) {
            return null;      // drop — no read access: a locked channel for this viewer, or hidden
        }
        return message;
    }
}
