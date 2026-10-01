package oak.server.dto;

/**
 * A voice-channel invitation (the sidebar's hover "invite a friend to this room"): the
 * sender posts {@code {channelId, to}} to {@code /app/voice.invite}; the relay stamps
 * {@code from} with the authenticated sender and RESOLVES {@code channelName} itself —
 * the receiver's channel store may not hold the channel at all (a hidden room, or a
 * background instance session whose snapshot predates it). Delivered to the target's
 * {@code /user/queue/voice.invite} as a live card; an offline receiver simply never
 * gets it (no persistence — the user decision, Discord's invite embed is not mirrored).
 */
public record VoiceInvite(
        Long channelId,
        String channelName,
        String from,
        String to
) {
}
