package oak.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Set;

/** Creates a custom role. {@code permissions} are permission names; omitted fields default. */
public record CreateRoleRequest(
        @NotBlank @Size(max = 100) String name,
        Set<String> permissions,
        Integer color,
        Boolean mentionable,
        Boolean hoist) {
}
