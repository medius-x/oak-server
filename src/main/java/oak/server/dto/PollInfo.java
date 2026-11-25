package oak.server.dto;

import java.time.Instant;
import java.util.List;

/**
 * The poll attached to a message — the 17th (appended) {@code MessageResponse} field. Carries
 * the poll's shape and lifecycle, never any vote data: results live in {@link PollResults} and
 * reach a client only through its OWN actions (vote / show results) — the live {@code .poll}
 * push carries just the headline counters, so a spectator never receives percentages by
 * eavesdropping. WIRE: fields append-only; the client mirrors this record.
 */
public record PollInfo(
        Long id,
        String question,
        boolean allowMultiple,
        boolean anonymous,
        boolean allowVoteChange,
        Instant endsAt,
        Instant closedAt,
        List<PollOptionInfo> options
) {

    /** One answer option (creation order). */
    public record PollOptionInfo(Long id, String text) {
    }
}
