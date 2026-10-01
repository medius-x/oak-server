package oak.server.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import oak.server.dto.ChannelMyPermissions;
import oak.server.dto.ChannelResponse;
import oak.server.dto.CreateChannelRequest;
import oak.server.dto.RenameChannelRequest;
import oak.server.dto.ReorderChannelsRequest;
import oak.server.service.ChannelService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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

    /** The caller's viewer-filtered channel list: hidden channels (no VIEW_CHANNEL) are omitted,
     *  channels they can see but not use carry {@code locked=true}. */
    @GetMapping
    public List<ChannelResponse> list(@AuthenticationPrincipal UserDetails principal) {
        return channelService.list(principal.getUsername());
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

    /** Renames a TEXT/VOICE channel ({@code MANAGE_CHANNELS}); the route sits one segment deeper than
     *  the group-DM rename ({@code PATCH /api/channels/{id}}, owned by DmChannelController) so the two
     *  mappings never collide. DM ids are rejected with 400 by the service. */
    @PatchMapping("/{id}/name")
    public ChannelResponse rename(@PathVariable Long id,
                                  @RequestBody @Valid RenameChannelRequest request,
                                  @AuthenticationPrincipal UserDetails principal) {
        return channelService.rename(id, request.name(), principal.getUsername());
    }

    /** The signed-in member's resolved (override-aware) SEND_MESSAGES / ATTACH_FILES on this channel —
     *  for the client to hide the composer when the member can't write here. */
    @GetMapping("/{id}/permissions/me")
    public ChannelMyPermissions myPermissions(@PathVariable Long id,
                                              @AuthenticationPrincipal UserDetails principal) {
        return channelService.myPermissions(id, principal.getUsername());
    }

    /** The full-sidebar reorder (the drag-drop persist; {@code /api/roles/order} precedent):
     *  every non-DM channel and category once, in the intended order, each with its parent.
     *  {@code MANAGE_CHANNELS} is enforced in the service; clients refetch on the
     *  channels-changed ping that fires after the commit. */
    @PutMapping("/order")
    public void reorder(@RequestBody @Valid ReorderChannelsRequest request,
                        @AuthenticationPrincipal UserDetails principal) {
        channelService.reorder(request, principal.getUsername());
    }
}
