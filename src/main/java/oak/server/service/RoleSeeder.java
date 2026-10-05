package oak.server.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import oak.server.entity.Permission;
import oak.server.entity.Permissions;
import oak.server.entity.Role;
import oak.server.entity.RoleKind;
import oak.server.repository.RoleRepository;
import oak.server.repository.UserRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotently seeds the two built-in roles and guarantees an OWNER exists:
 * <ul>
 *   <li><b>@everyone</b> — {@code kind=EVERYONE}, {@code position=0}, a sane default permission set
 *       (read/send text, attach, connect & speak in voice) so a brand-new member can still use the
 *       app. Never stored in {@code user_roles}; {@code AuthorizationService} folds it into every
 *       member's resolution unconditionally.</li>
 *   <li><b>OWNER</b> — {@code kind=OWNER}, the highest position, {@code ADMINISTRATOR} (all perms).
 *       Protected: cannot be deleted, renamed, or freely assigned.</li>
 * </ul>
 *
 * <p>The first member registered (see {@code AuthService.register}) is granted OWNER. As a fallback
 * for an already-populated database that predates RBAC (no member holds OWNER), the earliest member
 * is promoted here at startup.
 *
 * <p>Runs as an {@link ApplicationRunner} so the roles are committed before any HTTP request (and
 * before {@code AuthService.register} can need them). The test profile (create-drop) is unaffected.
 */
@Slf4j
@Component
@Order(0)
@RequiredArgsConstructor
public class RoleSeeder implements ApplicationRunner {

    /** Position of the OWNER role — far above any custom role (which are small dense integers). */
    public static final int OWNER_POSITION = Integer.MAX_VALUE / 2;

    /** Defaults granted to everyone — keeps the app usable for members with no other roles.
     *  EMBED_LINKS was dropped when the bit retired (2026-10-02): V16 clears it from existing
     *  rows, and fresh seeds must not re-set it. */
    static final long EVERYONE_DEFAULTS = Permissions.union(
            Permission.SEND_MESSAGES.bit,
            Permission.READ_MESSAGE_HISTORY.bit,
            Permission.ATTACH_FILES.bit,
            Permission.ADD_REACTIONS.bit,
            Permission.CONNECT.bit,
            Permission.SPEAK.bit,
            Permission.STREAM.bit,
            Permission.USE_VAD.bit,
            Permission.CHANGE_NICKNAME.bit,
            Permission.VIEW_CHANNEL.bit);

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedEveryone();
        ensureEveryoneViewChannel();
        ensureEveryoneChangeNickname();
        ensureEveryoneAddReactions();
        ensureEveryoneStream();
        seedOwner();
        ensureOwnerAssigned();
    }

    private void seedEveryone() {
        if (roleRepository.findByKind(RoleKind.EVERYONE).isPresent()) {
            return;
        }
        Role everyone = new Role("@everyone", 0, EVERYONE_DEFAULTS, false, false, RoleKind.EVERYONE);
        roleRepository.save(everyone);
        log.info("[RBAC] Seeded @everyone role");
    }

    /** Unconditionally re-grants {@link Permission#VIEW_CHANNEL} to the stored @everyone row on every
     *  boot. VIEW is treated as <i>infrastructure</i>: @everyone must hold it server-wide (private
     *  channels deny it per-channel / per-role, never on the base row), so a database seeded before
     *  the bit existed — or one where the bit was flipped off by hand — would otherwise turn EVERY
     *  channel invisible for every non-owner the moment the master gate ships. This failure mode
     *  is instance-wide darkness, hence the automatic repair (CHANGE_NICKNAME and ADD_REACTIONS
     *  self-repair the same way below). Public for a direct integration test. */
    public void ensureEveryoneViewChannel() {
        roleRepository.findByKind(RoleKind.EVERYONE).ifPresent(everyone -> {
            if ((everyone.getPermissions() & Permission.VIEW_CHANNEL.bit) != 0) {
                return;
            }
            everyone.setPermissions(everyone.getPermissions() | Permission.VIEW_CHANNEL.bit);
            roleRepository.save(everyone);
            log.warn("[RBAC] @everyone was missing VIEW_CHANNEL — re-granted (private channels stay "
                    + "managed by per-channel overrides only)");
        });
    }

    /** Unconditionally re-grants {@link Permission#CHANGE_NICKNAME} to the stored @everyone row on
     *  every boot, mirroring {@link #ensureEveryoneViewChannel()}. The self-rename feature is meant
     *  to be live for every ordinary member (Discord semantics — the owner may still revoke the bit
     *  per-role/per-channel), and databases seeded before the bit was added to {@link
     *  #EVERYONE_DEFAULTS} would otherwise show the feature only to OWNER. Unlike VIEW_CHANNEL this
     *  is not infrastructure — but the same one-line repair beats a manual SQL migration note. */
    public void ensureEveryoneChangeNickname() {
        roleRepository.findByKind(RoleKind.EVERYONE).ifPresent(everyone -> {
            if ((everyone.getPermissions() & Permission.CHANGE_NICKNAME.bit) != 0) {
                return;
            }
            everyone.setPermissions(everyone.getPermissions() | Permission.CHANGE_NICKNAME.bit);
            roleRepository.save(everyone);
            log.warn("[RBAC] @everyone was missing CHANGE_NICKNAME — re-granted (self-rename stays "
                    + "available to ordinary members)");
        });
    }

    /** Unconditionally re-grants {@link Permission#ADD_REACTIONS} to the stored @everyone row on
     *  every boot, mirroring {@link #ensureEveryoneChangeNickname()}. ADD_REACTIONS joined {@link
     *  #EVERYONE_DEFAULTS} after live databases had already been seeded, and the idempotent seeder
     *  never touches an existing row — so ordinary members (the owner bypasses via
     *  ADMINISTRATOR, which is why the smoke missed it) silently lost the whole reactions
     *  feature. Replaces the old manual-migration note ("UPDATE roles ... | 262144"). */
    public void ensureEveryoneAddReactions() {
        roleRepository.findByKind(RoleKind.EVERYONE).ifPresent(everyone -> {
            if ((everyone.getPermissions() & Permission.ADD_REACTIONS.bit) != 0) {
                return;
            }
            everyone.setPermissions(everyone.getPermissions() | Permission.ADD_REACTIONS.bit);
            roleRepository.save(everyone);
            log.warn("[RBAC] @everyone was missing ADD_REACTIONS — re-granted (reactions stay "
                    + "available to ordinary members)");
        });
    }

    /** Unconditionally re-grants {@link Permission#STREAM} to the stored @everyone row on every
     *  boot, mirroring {@link #ensureEveryoneAddReactions()}. STREAM joined {@link
     *  #EVERYONE_DEFAULTS} with the security round 6 client gate (the share/camera buttons read
     *  it) — every live database was seeded without the bit, and without the repair the buttons
     *  would vanish for ordinary members on deploy day; revoking the bit per-role/per-channel is
     *  the intended way to take streaming away, never flipping the base row. */
    public void ensureEveryoneStream() {
        roleRepository.findByKind(RoleKind.EVERYONE).ifPresent(everyone -> {
            if ((everyone.getPermissions() & Permission.STREAM.bit) != 0) {
                return;
            }
            everyone.setPermissions(everyone.getPermissions() | Permission.STREAM.bit);
            roleRepository.save(everyone);
            log.warn("[RBAC] @everyone was missing STREAM — re-granted (screen share stays "
                    + "available to ordinary members)");
        });
    }

    private void seedOwner() {
        if (roleRepository.findByKind(RoleKind.OWNER).isPresent()) {
            return;
        }
        Role owner = new Role("OWNER", OWNER_POSITION, Permissions.ALL, false, false, RoleKind.OWNER);
        roleRepository.save(owner);
        log.info("[RBAC] Seeded OWNER role");
    }

    /** If no member currently holds OWNER (e.g. a pre-RBAC database), promote the earliest member. */
    private void ensureOwnerAssigned() {
        if (userRepository.existsUserWithRoleKind(RoleKind.OWNER)) {
            return;
        }
        userRepository.findFirstByOrderByIdAsc().ifPresent(user -> {
            Role owner = roleRepository.findByKind(RoleKind.OWNER).orElseThrow();
            user.getRoles().add(owner);
            userRepository.save(user);
            log.info("[RBAC] No OWNER found — promoted earliest member '{}' to OWNER", user.getUsername());
        });
    }
}
