package oak.server.dto;

/**
 * Payload of a {@code /app/voice.join} / {@code /app/voice.leave} STOMP SEND: the voice channel
 * being joined (the leave handler ignores the id — the server knows the sender's room by session).
 */
public record VoiceCommand(Long channelId) {
}
