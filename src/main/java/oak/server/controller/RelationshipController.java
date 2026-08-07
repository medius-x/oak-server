package oak.server.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import oak.server.dto.AddFriendRequest;
import oak.server.dto.BlockRequest;
import oak.server.dto.RelationshipResponse;
import oak.server.service.RelationshipService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The social graph: list relationships, send/accept/cancel friend requests, unfriend, block/unblock.
 * All endpoints are under {@code /api/users/me/...} and keyed to the authenticated user. Mutations and
 * event publishing live in {@link RelationshipService}.
 */
@RestController
@RequestMapping("/api/users/me")
@RequiredArgsConstructor
public class RelationshipController {

    private final RelationshipService relationshipService;

    @GetMapping("/relationships")
    public List<RelationshipResponse> list(@AuthenticationPrincipal UserDetails principal) {
        return relationshipService.listMine(principal.getUsername());
    }

    @PostMapping("/friends")
    @ResponseStatus(HttpStatus.CREATED)
    public RelationshipResponse addFriend(@RequestBody @Valid AddFriendRequest request,
                                          @AuthenticationPrincipal UserDetails principal) {
        return relationshipService.sendRequest(principal.getUsername(), request.username());
    }

    @PutMapping("/friends/{userId}")
    public RelationshipResponse accept(@PathVariable Long userId,
                                       @AuthenticationPrincipal UserDetails principal) {
        return relationshipService.accept(principal.getUsername(), userId);
    }

    @DeleteMapping("/friends/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable Long userId,
                       @AuthenticationPrincipal UserDetails principal) {
        relationshipService.removeRelationship(principal.getUsername(), userId);
    }

    @PostMapping("/blocks")
    @ResponseStatus(HttpStatus.CREATED)
    public RelationshipResponse block(@RequestBody @Valid BlockRequest request,
                                      @AuthenticationPrincipal UserDetails principal) {
        return relationshipService.block(principal.getUsername(), request.username());
    }

    @DeleteMapping("/blocks/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unblock(@PathVariable Long userId,
                        @AuthenticationPrincipal UserDetails principal) {
        relationshipService.unblock(principal.getUsername(), userId);
    }
}
