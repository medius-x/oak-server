package oak.server.dto;

import java.util.List;

/**
 * A poll's tallied results, returned by a vote (instant feedback) and by "show results". The
 * headline {@code totalVoters} counts DISTINCT people (a multi-choice voter counts once — user
 * decision). {@code voters} per option carries usernames ONLY for non-anonymous polls — an
 * anonymous poll's rows ship an empty list, so the wire never even hints at who picked what.
 */
public record PollResults(
        PollInfo poll,
        int totalVoters,
        List<PollOptionResult> options
) {

    /** One option's tally. {@code voters} is empty for anonymous polls (by design, see class). */
    public record PollOptionResult(Long optionId, int votes, List<String> voters) {
    }
}
