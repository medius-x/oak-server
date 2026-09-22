package oak.server.service;

import oak.server.entity.Channel;
import oak.server.entity.Message;

/**
 * Spring application event (published by {@code MessageService.create}, consumed by
 * {@code DiscussionService}): a message landed in a DISCUSSION channel. Exists to keep the
 * two services acyclical — {@code DiscussionService} depends on {@code MessageService} for
 * the create-with-first-message flow, so the "post landed" hook cannot be a direct call the
 * other way. Synchronous ({@code @EventListener} runs inside the publishing transaction —
 * the same semantics the direct call had).
 */
public record MessagePostedInDiscussion(Channel channel, Message message) {
}
