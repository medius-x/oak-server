package oak.server.dto;

import java.util.List;

/** One voice channel's current members, returned by the {@code GET /api/voice/members} snapshot. */
public record VoiceChannelMembersDto(Long channelId, List<String> usernames) {
}
