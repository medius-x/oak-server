package oak.server.dto;

import java.util.List;

/** One row of {@code GET /api/channels/{id}/polls/my-votes}: the caller's current
 *  selection in one poll of the channel, addressed by the POLL'S MESSAGE id (the
 *  client stores client-side state per message). Only polls the caller actually
 *  voted in appear — absence means "has not voted". */
public record MyPollVotesDto(
        Long messageId,
        Long pollId,
        List<Long> optionIds
) {
}
