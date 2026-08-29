package oak.server.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PATCH /api/server/connection} — sets the address the instance advertises for
 * client connections (host + port). {@code MANAGE_SERVER}-gated; the write path exists for the
 * future remote-provisioning flow and for setting the fields by hand on a pre-existing row.
 * Both fields are optional: {@code null} leaves that part unchanged; a non-null blank host is
 * rejected (there is no "clear the address" semantics — an instance is always reachable somehow).
 */
public record UpdateConnectionInfoRequest(
        @Size(max = 255) String connectionHost,
        @Min(1) @Max(65535) Integer connectionPort) {
}
