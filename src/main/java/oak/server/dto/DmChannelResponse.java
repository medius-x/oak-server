package oak.server.dto;

import oak.server.entity.Channel;
import oak.server.entity.Message;

import java.time.Instant;
import java.util.List;

/**
 * A DM / group-DM channel the caller participates in. Backs {@code GET /api/channels/dm}. Carries the
 * participant roster (compact {@link UserSummary}s), the owner for a group DM, and the last message
 * (id / timestamp / one-line preview / author) so the client can sort the DM list by recency and render
 * a preview without fetching each channel's history.
 *
 * <p>Unread / mention counts are <em>not</em> included: the existing {@code GET /api/read-states}
 * snapshot already covers DM channels for participants (the DM branch in
 * {@code AuthorizationService.hasInChannel} lets a participant's read-state through), so the client
 * joins DM ids with read-states client-side rather than duplicating the counts here.
 */
public record DmChannelResponse(Long id, String type, String name, Long ownerId,
                                List<UserSummary> participants,
                                Long lastMessageId, Instant lastMessageAt,
                                String lastMessagePreview, String lastMessageAuthorName) {

    public static DmChannelResponse from(Channel channel, List<UserSummary> participants, Message last) {
        return new DmChannelResponse(
                channel.getId(),
                channel.getType().name(),
                channel.getName(),
                channel.getOwner() == null ? null : channel.getOwner().getId(),
                participants,
                last == null ? null : last.getId(),
                last == null ? null : last.getCreatedAt(),
                last == null ? null : last.getContent(),
                // The rename model: a display name, never the login (the preview renders verbatim).
                last == null ? null : (last.getAuthor() == null ? null : last.getAuthor().displayName()));
    }
}
