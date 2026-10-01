package oak.server.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import oak.server.dto.OfferOwnershipRequest;
import oak.server.dto.OwnershipTransferResponse;
import oak.server.dto.PendingOwnershipResponse;
import oak.server.service.OwnershipTransferService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Server-ownership transfer (the DiscussionController idiom — full paths per method, no
 * class-level {@code @RequestMapping}): the pending snapshot, the owner's offer, and the
 * recipient's accept/decline / the owner's cancel. Gates live in the service (only the
 * owner may offer or cancel; only the recipient may accept or decline).
 */
@RestController
@RequiredArgsConstructor
public class OwnershipTransferController {

    private final OwnershipTransferService transferService;

    /** The live PENDING offer as seen by the caller — {@code {"pending": null}} for a
     *  non-party. Fetched at session start, so a pending offer survives the recipient
     *  being offline. */
    @GetMapping("/api/ownership/pending")
    public PendingOwnershipResponse pending(@AuthenticationPrincipal UserDetails principal) {
        return transferService.pending(principal.getUsername());
    }

    @PostMapping("/api/ownership/offer")
    @ResponseStatus(HttpStatus.CREATED)
    public OwnershipTransferResponse offer(@RequestBody @Valid OfferOwnershipRequest request,
                                           @AuthenticationPrincipal UserDetails principal) {
        return transferService.offer(principal.getUsername(), request.toUserId());
    }

    @PostMapping("/api/ownership/{transferId}/accept")
    public OwnershipTransferResponse accept(@PathVariable Long transferId,
                                            @AuthenticationPrincipal UserDetails principal) {
        return transferService.accept(principal.getUsername(), transferId);
    }

    @PostMapping("/api/ownership/{transferId}/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decline(@PathVariable Long transferId,
                        @AuthenticationPrincipal UserDetails principal) {
        transferService.decline(principal.getUsername(), transferId);
    }

    @PostMapping("/api/ownership/{transferId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable Long transferId,
                       @AuthenticationPrincipal UserDetails principal) {
        transferService.cancel(principal.getUsername(), transferId);
    }
}
