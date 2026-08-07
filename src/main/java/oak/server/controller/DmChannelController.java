package oak.server.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import oak.server.dto.AddParticipantRequest;
import oak.server.dto.CreateDmRequest;
import oak.server.dto.CreateGroupDmRequest;
import oak.server.dto.DmChannelResponse;
import oak.server.dto.UpdateDmRequest;
import oak.server.service.DmService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * DM / group-DM endpoints. Listing and opening a 1:1 DM are at {@code /api/channels/dm}; group-DM
 * creation, participant management, rename, and close are alongside. The close path is
 * {@code /api/channels/dm/{id}} (distinct segment count from the public {@code DELETE /api/channels/{id}},
 * which stays the admin channel-delete) so the two never collide. Messages, read-state, and attachments
 * for a DM channel flow through the existing {@code /api/channels/{id}/...} endpoints.
 */
@RestController
@RequestMapping("/api/channels")
@RequiredArgsConstructor
public class DmChannelController {

    private final DmService dmService;

    @GetMapping("/dm")
    public List<DmChannelResponse> list(@AuthenticationPrincipal UserDetails principal) {
        return dmService.listMine(principal.getUsername());
    }

    @PostMapping("/dm")
    public DmChannelResponse createDm(@RequestBody @Valid CreateDmRequest request,
                                      @AuthenticationPrincipal UserDetails principal) {
        return dmService.getOrCreateDm(principal.getUsername(), request.recipientId());
    }

    @PostMapping("/group-dm")
    @ResponseStatus(HttpStatus.CREATED)
    public DmChannelResponse createGroupDm(@RequestBody @Valid CreateGroupDmRequest request,
                                           @AuthenticationPrincipal UserDetails principal) {
        return dmService.createGroupDm(principal.getUsername(), request.participantIds(), request.name());
    }

    @PatchMapping("/{id}")
    public DmChannelResponse rename(@PathVariable Long id,
                                    @RequestBody @Valid UpdateDmRequest request,
                                    @AuthenticationPrincipal UserDetails principal) {
        return dmService.rename(principal.getUsername(), id, request.name());
    }

    @PostMapping("/{id}/participants")
    public DmChannelResponse addParticipant(@PathVariable Long id,
                                            @RequestBody @Valid AddParticipantRequest request,
                                            @AuthenticationPrincipal UserDetails principal) {
        return dmService.addParticipant(principal.getUsername(), id, request.userId());
    }

    @DeleteMapping("/{id}/participants/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeParticipant(@PathVariable Long id,
                                  @PathVariable Long userId,
                                  @AuthenticationPrincipal UserDetails principal) {
        dmService.removeParticipant(principal.getUsername(), id, userId);
    }

    /** Close a DM (1:1: either participant; group: owner-only). Distinct from the public channel delete. */
    @DeleteMapping("/dm/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void close(@PathVariable Long id,
                      @AuthenticationPrincipal UserDetails principal) {
        dmService.close(principal.getUsername(), id);
    }
}
