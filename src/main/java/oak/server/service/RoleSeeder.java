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

    /** Defaults granted to everyone — keeps the app usable for members with no other roles. */
    static final long EVERYONE_DEFAULTS = Permissions.union(
            Permission.SEND_MESSAGES.bit,
            Permission.READ_MESSAGE_HISTORY.bit,
            Permission.ATTACH_FILES.bit,
            Permission.EMBED_LINKS.bit,
            Permission.ADD_REACTIONS.bit,
            Permission.CONNECT.bit,
            Permission.SPEAK.bit,
            Permission.USE_VAD.bit);

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedEveryone();
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
