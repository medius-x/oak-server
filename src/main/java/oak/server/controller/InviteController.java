package oak.server.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import oak.server.dto.InviteResponse;
import oak.server.dto.InviteSettingsRequest;
import oak.server.dto.InviteSettingsResponse;
import oak.server.dto.InvitesStatusResponse;
import oak.server.entity.Invite;
import oak.server.entity.Permission;
import oak.server.entity.ServerProfile;
import oak.server.entity.User;
import oak.server.repository.InviteRepository;
import oak.server.repository.ServerProfileRepository;
import oak.server.service.AuthorizationService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * Invite-link management (the "Invites" server-settings section), gated by
 * {@link Permission#MANAGE_MEMBERS} throughout — the user decision mirroring Discord's
 * invite model: whoever can add members can hand out the links that let them in. Every
 * mutation pings {@value #INVITE_TOPIC} (a bare Long timestamp, the sticker/emoji
 * precedent); the section refetches on its own mutations, the ping is the hook for a
 * future live subscription.
 *
 * <p>Two route roots deliberately live on ONE controller with explicit full paths and NO
 * class-level {@code @RequestMapping} prefix (the DiscussionController idiom): the
 * {@code /api/invites} tree and the TTL endpoint {@code PUT /api/server/invite-settings}.
 * The latter stays out of {@code ServerProfileController} on purpose: that controller is
 * the MANAGE_SERVER domain, while the TTL belongs to the invites feature under
 * MANAGE_MEMBERS; filing it there would make a members-manager's own settings screen 403
 * on its TTL field. The wire of {@code GET /api/server} is not touched at all.
 */
@RestController
@RequiredArgsConstructor
public class InviteController {

    /** STOMP topic pinged (a bare Long timestamp) after every invite mutation. */
    public static final String INVITE_TOPIC = "/topic/invites";

    /** Code default for {@code invite_ttl_minutes} on profile rows predating the column (a day). */
    static final int DEFAULT_TTL_MINUTES = 1440;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final InviteRepository inviteRepository;
    private final ServerProfileRepository profileRepository;
    private final AuthorizationService auth;
    private final SimpMessagingTemplate messagingTemplate;

    /** The section in one fetch: the TTL setting plus the active links. */
    @GetMapping("/api/invites")
    public InvitesStatusResponse list(@AuthenticationPrincipal UserDetails principal,
                                      @RequestHeader(value = HttpHeaders.HOST, required = false) String hostHeader) {
        requireManageMembers(principal);
        ServerProfile profile = profileRepository.singleton();
        List<InviteResponse> invites = inviteRepository.findActive(Instant.now()).stream()
                .map(invite -> InviteResponse.from(invite, buildLink(profile, hostHeader, invite.getSecret())))
                .toList();
        return new InvitesStatusResponse(ttlMinutes(profile), invites);
    }

    /** Mints one invite link ("Create invite"): a fresh 32-char base64url secret, expiring
     *  after the CURRENT TTL setting. */
    @PostMapping("/api/invites")
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    public InviteResponse create(@AuthenticationPrincipal UserDetails principal,
                                 @RequestHeader(value = HttpHeaders.HOST, required = false) String hostHeader) {
        User actor = requireManageMembers(principal);
        ServerProfile profile = profileRepository.singleton();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(Duration.ofMinutes(ttlMinutes(profile)));
        Invite invite = new Invite(mintSecret(), actor, expiresAt);
        try {
            invite = inviteRepository.save(invite);
        } catch (DataIntegrityViolationException race) {
            // 32 random chars colliding with an existing secret — astronomically unlikely,
            // but the unique constraint is the authority; one regen, then give up loudly.
            invite = inviteRepository.save(new Invite(mintSecret(), actor, expiresAt));
        }
        publishPingAfterCommit();
        return InviteResponse.from(invite, buildLink(profile, hostHeader, invite.getSecret()));
    }

    /** "Expire": revokes one link. 204 for any existing invite (idempotent for an already
     *  inactive one); 404 only for an id that never existed. */
    @DeleteMapping("/api/invites/{id}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal UserDetails principal, @PathVariable Long id) {
        requireManageMembers(principal);
        Invite invite = inviteRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown invite"));
        if (invite.isActive(Instant.now())) {
            invite.setRevoked(true);
            inviteRepository.save(invite);
            publishPingAfterCommit();
        }
    }

    /** "Expire all": revokes every active link in one shot. */
    @PostMapping("/api/invites/expire-all")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void expireAll(@AuthenticationPrincipal UserDetails principal) {
        requireManageMembers(principal);
        inviteRepository.revokeAllActive(Instant.now());
        publishPingAfterCommit();
    }

    /**
     * Changes the TTL. Retroactive by user decision: every ACTIVE invite is rescheduled to
     * {@code createdAt + newTtl} — lowering the TTL may kill links instantly (that is the
     * point of "retroactive"); used/expired/revoked rows are never resurrected. The
     * reschedule is a load-and-loop, not a JPQL {@code expiresAt = createdAt + :ttl}
     * arithmetic (a dialect risk on H2; the active set is a handful of rows).
     */
    @PutMapping("/api/server/invite-settings")
    @Transactional
    public InviteSettingsResponse updateSettings(@AuthenticationPrincipal UserDetails principal,
                                                 @RequestBody @Valid InviteSettingsRequest request) {
        requireManageMembers(principal);
        ServerProfile profile = profileRepository.singleton();
        profile.setInviteTtlMinutes(request.ttlMinutes());
        profileRepository.save(profile);
        Duration ttl = Duration.ofMinutes(request.ttlMinutes());
        for (Invite invite : inviteRepository.findActive(Instant.now())) {
            invite.setExpiresAt(invite.getCreatedAt().plus(ttl));
        }
        publishPingAfterCommit();
        return new InviteSettingsResponse(request.ttlMinutes());
    }

    private User requireManageMembers(UserDetails principal) {
        User actor = auth.requireUser(principal.getUsername());
        auth.requireServer(actor, Permission.MANAGE_MEMBERS);
        return actor;
    }

    private void publishPing() {
        messagingTemplate.convertAndSend(INVITE_TOPIC, System.currentTimeMillis());
    }

    /** Pings AFTER the surrounding tx commits: an in-tx ping lets subscribers refetch
     *  before the mutation is visible — their snapshot would still read the old state
     *  (the mint would be missing, the expiry stale). No active synchronization (a
     *  no-tx caller) pings directly. Public for {@code AuthService}, which claims a
     *  link inside its own transaction. */
    public static void publishPingAfterCommit(SimpMessagingTemplate messagingTemplate) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            messagingTemplate.convertAndSend(INVITE_TOPIC, System.currentTimeMillis());
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                messagingTemplate.convertAndSend(INVITE_TOPIC, System.currentTimeMillis());
            }
        });
    }

    private void publishPingAfterCommit() {
        publishPingAfterCommit(messagingTemplate);
    }

    static int ttlMinutes(ServerProfile profile) {
        return profile.getInviteTtlMinutes() != null ? profile.getInviteTtlMinutes() : DEFAULT_TTL_MINUTES;
    }

    /**
     * Assembles the copyable link. The host/port come from the ADVERTISED connection info
     * (what members type into "Add server" — the administering client may sit on localhost
     * while the link must carry the LAN address); with nothing advertised, the request's
     * Host header is the best guess. The scheme is always {@code http}: the profile stores
     * no scheme, and TLS arrives with future provisioning. The secret rides the hash
     * fragment — clients parse it locally and submit it in the register body.
     */
    static String buildLink(ServerProfile profile, String hostHeader, String secret) {
        String host = profile.getConnectionHost() != null && !profile.getConnectionHost().isBlank()
                ? profile.getConnectionHost()
                : hostHeader != null && !hostHeader.isBlank() ? hostHeader : "localhost";
        String port = profile.getConnectionHost() != null && profile.getConnectionPort() != null
                ? ":" + profile.getConnectionPort()
                : ""; // the Host header already carries its port, if any
        return "http://" + host + port + "/#invite=" + secret;
    }

    private static String mintSecret() {
        byte[] randomBytes = new byte[24]; // 192 bits → 32 base64url chars, the refresh-token idiom
        SECURE_RANDOM.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }
}
