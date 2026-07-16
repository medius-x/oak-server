package oak.server.dto;

/** Voice-room membership delta pushed over {@code /topic/voice} (serialized by Jackson as its name). */
public enum VoiceMemberStatus {
    JOINED,
    LEFT
}
