package oak.server.dto;

/** The VOICE room's participant limit ({@code PATCH /api/channels/{id}/user-limit}):
 *  {@code null} CLEARS the limit (back to unlimited) — an absent field is a clear, so the
 *  "no limit" state is expressible without a sentinel number. Range 1..99 is validated by the
 *  service. */
public record UpdateChannelLimitRequest(Integer userLimit) {
}
