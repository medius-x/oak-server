package oak.server.dto;

import java.util.List;
import java.util.Map;

/**
 * One voice channel's current members, returned by the {@code GET /api/voice/members} snapshot.
 *
 * <p>{@code marks} (2026-11-24) carries each member's {@link VoiceControlState} so a viewer who
 * NEVER entered the room can still paint the mute/deafen indicators from the initial snapshot —
 * before, {@code /topic/voice.state} was push-only with no seed, so the icons only appeared after
 * a live flip or the viewer's own join (Discord semantics: the marks are ALWAYS visible). The
 * field is APPEND-ONLY: an older client ignores it, a newer client on an older server simply
 * gets no seed and degrades to the push-only behavior.
 */
public record VoiceChannelMembersDto(Long channelId, List<String> usernames,
                                     Map<String, VoiceControlState> marks) {

    /** The pre-marks shape (no control state carried) — internal callers and tests. */
    public VoiceChannelMembersDto(Long channelId, List<String> usernames) {
        this(channelId, usernames, Map.of());
    }
}
