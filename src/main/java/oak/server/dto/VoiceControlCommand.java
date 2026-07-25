package oak.server.dto;

/**
 * Payload of a {@code /app/voice.mute} | {@code /voice.deafen} | {@code /voice.kick} STOMP SEND from a
 * member who is acting on another. {@code muted}/{@code deafened} carry the desired state for the
 * mute/deafen endpoints (ignored by kick); {@code targetUsername} is who is being acted on.
 */
public record VoiceControlCommand(Long channelId, String targetUsername, Boolean muted, Boolean deafened) {
}
