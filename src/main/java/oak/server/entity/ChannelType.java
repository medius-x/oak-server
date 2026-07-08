package oak.server.entity;

/** Channel type. Stored as a string column (see {@code @Enumerated(STRING)} on {@link Channel}). */
public enum ChannelType {
    TEXT,
    VOICE
}
