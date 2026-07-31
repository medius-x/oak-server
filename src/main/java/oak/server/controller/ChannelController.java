package oak.server.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import oak.server.dto.ChannelMyPermissions;
import oak.server.dto.ChannelResponse;
import oak.server.dto.CreateChannelRequest;
import oak.server.service.ChannelService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Channel CRUD. All endpoints require a valid JWT (only {@code /api/auth/**} is public).
 * Creating a channel requires {@code MANAGE_CHANNELS} (enforced in {@link ChannelService}).
 */
@RestController
@RequestMapping("/api/channels")
@RequiredArgsConstructor
public class ChannelController {

    private final ChannelService channelService;

    @GetMapping
    public List<ChannelResponse> list() {
        return channelService.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ChannelResponse create(@RequestBody @Valid CreateChannelRequest request,
                                  @AuthenticationPrincipal UserDetails principal) {
        return channelService.create(request, principal.getUsername());
    }

    /** Deletes a channel (and its messages + overrides). Requires {@code MANAGE_CHANNELS} (enforced in
     *  {@link ChannelService}); returns 204 on success, 404 if the channel no longer exists. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, @AuthenticationPrincipal UserDetails principal) {
        channelService.delete(id, principal.getUsername());
    }

    /** The signed-in member's resolved (override-aware) SEND_MESSAGES / ATTACH_FILES on this channel —
     *  for the client to hide the composer when the member can't write here. */
    @GetMapping("/{id}/permissions/me")
    public ChannelMyPermissions myPermissions(@PathVariable Long id,
                                              @AuthenticationPrincipal UserDetails principal) {
        return channelService.myPermissions(id, principal.getUsername());
    }
}
