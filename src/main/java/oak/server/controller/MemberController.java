package oak.server.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import oak.server.dto.AssignRolesRequest;
import oak.server.dto.MemberResponse;
import oak.server.dto.RenameMemberRequest;
import oak.server.service.MemberService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Member management — the management roster (members + roles), role assignment, remove, ban / unban.
 * List is open; mutations require the matching permission (+ hierarchy), enforced in
 * {@link MemberService}. Distinct from the presence roster ({@code GET /api/users}).
 */
@RestController
@RequestMapping("/api/members")
@RequiredArgsConstructor
public class MemberController {

    private final MemberService memberService;

    @GetMapping
    public List<MemberResponse> list() {
        return memberService.list();
    }

    @GetMapping("/bans")
    public List<MemberResponse> bans() {
        return memberService.banned();
    }

    @PutMapping("/{userId}/roles")
    public MemberResponse assignRoles(@PathVariable Long userId,
                                      @RequestBody @Valid AssignRolesRequest request,
                                      @AuthenticationPrincipal UserDetails principal) {
        return memberService.assignRoles(userId, request, principal.getUsername());
    }

    /** Sets the member's NICKNAME (the display name mentions resolve against). Someone else:
     *  MANAGE_NICKNAMES + hierarchy; yourself: CHANGE_NICKNAME. 409 on a taken nickname. */
    @PatchMapping("/{userId}/nickname")
    public MemberResponse rename(@PathVariable Long userId,
                                 @RequestBody @Valid RenameMemberRequest request,
                                 @AuthenticationPrincipal UserDetails principal) {
        return memberService.rename(userId, request, principal.getUsername());
    }

    /** Resets the nickname to the member's login handle. */
    @DeleteMapping("/{userId}/nickname")
    public MemberResponse resetNickname(@PathVariable Long userId,
                                        @AuthenticationPrincipal UserDetails principal) {
        return memberService.resetNickname(userId, principal.getUsername());
    }

    /** "Remove from server" — permanent (the reworked kick): the account is anonymized and can never
     *  rejoin. KICK_MEMBERS + hierarchy, enforced in {@link MemberService}. */
    @PostMapping("/{userId}/remove")
    public void remove(@PathVariable Long userId,
                       @AuthenticationPrincipal UserDetails principal) {
        memberService.remove(userId, principal.getUsername());
    }

    @PostMapping("/{userId}/ban")
    public void ban(@PathVariable Long userId,
                    @AuthenticationPrincipal UserDetails principal) {
        memberService.ban(userId, principal.getUsername());
    }

    @DeleteMapping("/{userId}/ban")
    public void unban(@PathVariable Long userId,
                      @AuthenticationPrincipal UserDetails principal) {
        memberService.unban(userId, principal.getUsername());
    }
}
