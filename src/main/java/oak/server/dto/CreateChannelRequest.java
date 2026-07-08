package oak.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import oak.server.entity.ChannelType;

public record CreateChannelRequest(
        @NotBlank @Size(max = 100) String name,
        ChannelType type // optional; defaults to TEXT
) {
}
