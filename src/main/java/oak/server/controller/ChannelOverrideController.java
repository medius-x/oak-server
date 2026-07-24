package oak.server.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import oak.server.dto.ChannelOverrideResponse;
import oak.server.dto.UpsertOverrideRequest;
import oak.server.service.ChannelOverrideService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Per-channel permission overrides. List is open to any member; create/replace/delete require
 * {@code MANAGE_CHANNELS} (enforced in {@link ChannelOverrideService}). Path is distinct from
 * {@code /api/channels/{id}/room} and the channel/messages controllers.
 */
@RestController
@RequestMapping("/api/channels/{channelId}/overrides")
@RequiredArgsConstructor
public class ChannelOverrideController {

    private final ChannelOverrideService channelOverrideService;

    @GetMapping
    public List<ChannelOverrideResponse> list(@PathVariable Long channelId) {
        return channelOverrideService.list(channelId);
    }

    @PutMapping("/{roleId}")
    public ChannelOverrideResponse upsert(@PathVariable Long channelId,
                                          @PathVariable Long roleId,
                                          @RequestBody @Valid UpsertOverrideRequest request,
                                          @AuthenticationPrincipal UserDetails principal) {
        return channelOverrideService.upsert(channelId, roleId, request, principal.getUsername());
    }

    @DeleteMapping("/{roleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long channelId,
                       @PathVariable Long roleId,
                       @AuthenticationPrincipal UserDetails principal) {
        channelOverrideService.delete(channelId, roleId, principal.getUsername());
    }
}
