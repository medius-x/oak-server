package oak.server.dto;

/**
 * Targeted at the affected member via {@code /user/queue/voice.control} ({@code convertAndSendToUser})
 * for actions that need <i>that client</i> to react — currently a kick ({@code type=KICKED}): the member
 * tears down its Janus connection and leaves the room. {@code byUsername} is who acted, for the UI message.
 */
public record VoiceControlEvent(String type, Long channelId, String byUsername) {
}
