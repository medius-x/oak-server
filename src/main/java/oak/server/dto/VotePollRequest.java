package oak.server.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/** {@code POST /api/polls/{pollId}/vote} — the voter's full selection (one id for a
 *  single-choice poll, several for a multi-choice one). A re-vote replaces the whole set. */
public record VotePollRequest(
        @NotNull List<Long> optionIds
) {
}
