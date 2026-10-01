package oak.server.dto;

import jakarta.validation.constraints.NotNull;

/** {@code POST /api/ownership/offer} body — the member who will receive the OWNER role offer. */
public record OfferOwnershipRequest(@NotNull Long toUserId) {
}
