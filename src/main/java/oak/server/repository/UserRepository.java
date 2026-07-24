package oak.server.repository;

import oak.server.entity.RoleKind;
import oak.server.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * JPA repository for {@link User}. Derived query methods cover the lookups
 * needed later by auth (Spring Security + JWT).
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    /** Like {@link #findByUsername} but eager-fetches the member's roles (JOIN FETCH), so permission
     *  resolution can read {@code user.getRoles()} on threads with no Hibernate session (STOMP
     *  {@code clientInboundChannel} has no OpenEntityManagerInView, unlike HTTP requests). */
    @Query("select u from User u left join fetch u.roles where u.username = :username")
    Optional<User> findByUsernameWithRoles(@Param("username") String username);

    boolean existsByUsername(String username);

    /** All users in username order — backs the public roster ({@code GET /api/users}). */
    List<User> findAllByOrderByUsernameAsc();

    /** All users in id order — backs {@code GET /api/members} and the OWNER-bootstrap fallback. */
    List<User> findAllByOrderByIdAsc();

    /** The earliest-registered user (the OWNER fallback target when no owner exists). */
    Optional<User> findFirstByOrderByIdAsc();

    /** Whether any member currently holds a role of the given kind (used to detect an existing OWNER). */
    @Query("select case when count(u) > 0 then true else false end "
            + "from User u join u.roles r where r.kind = :kind")
    boolean existsUserWithRoleKind(@Param("kind") RoleKind kind);

    /** Members holding a given role (used to clear assignments before deleting a role). */
    @Query("select u from User u join u.roles r where r.id = :roleId")
    List<User> findUsersByRoleId(@Param("roleId") Long roleId);
}
