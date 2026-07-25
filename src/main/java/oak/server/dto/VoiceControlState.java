package oak.server.dto;

/**
 * Server-side mute/deafen state for one member in a voice room, broadcast to {@code /topic/voice.state}.
 * Every client uses it to paint the server-muted/deafened indicator on that member's row; the affected
 * member (matching {@code username}) additionally complies — forces its mic/speaker to the state.
 */
public record VoiceControlState(String username, Long channelId, boolean muted, boolean deafened) {
}
