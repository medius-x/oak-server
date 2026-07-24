package oak.server.repository;

import oak.server.entity.Role;
import oak.server.entity.RoleKind;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, Long> {

    Optional<Role> findByName(String name);

    /** The single built-in role of the given kind ({@code @everyone} / {@code OWNER}). */
    Optional<Role> findByKind(RoleKind kind);

    boolean existsByName(String name);

    /** Roles highest-position-first (the order shown in the management UI). */
    List<Role> findAllByOrderByPositionDesc();

    /** Custom roles (highest position first) — used to place a newly created role. */
    List<Role> findByKindOrderByPositionDesc(RoleKind kind);
}
