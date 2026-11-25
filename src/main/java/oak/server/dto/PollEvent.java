package oak.server.dto;

/**
 * The live poll push on {@code /topic/channel.{id}.poll}: the headline counters only — never
 * per-option tallies (those travel solely in {@link PollResults} answers to a client's own
 * action). {@code closed=true} is the finish signal (deadline sweeper or early close) after
 * which the client swaps the card to its final-results view for everyone.
 */
public record PollEvent(
        Long channelId,
        Long messageId,
        Long pollId,
        int totalVoters,
        boolean closed
) {
}
