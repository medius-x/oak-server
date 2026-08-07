package oak.server.dto;

import java.util.List;

/**
 * Pushed to {@code /user/queue/dm.channels} to tell a participant their DM-channel set changed — a DM
 * was created, a participant was added or removed, a group was renamed, or a DM was closed. The full
 * participant roster is included so the client can render the row without an extra fetch. {@code type}
 * ∈ {@code CREATED, PARTICIPANT_ADDED, PARTICIPANT_REMOVED, RENAMED, CLOSED}.
 */
public record DmChannelEvent(String type, Long channelId, String channelType, String name, Long ownerId,
                             List<UserSummary> participants) {
}
