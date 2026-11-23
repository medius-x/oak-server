package oak.server.dto;

import jakarta.validation.constraints.Size;

/**
 * {@code PATCH /api/server/sgdb}: null {@code apiKey} keeps the stored key, an
 * EMPTY STRING clears it (the Disconnect button), any other value stores it.
 * The key is a free per-account token from
 * {@code steamgriddb.com/profile/preferences/api}. {@code paused} is the
 * INDEPENDENT pause switch (null keeps its position).
 */
public record UpdateSgdbKeyRequest(@Size(max = 128) String apiKey, Boolean paused) {
}
