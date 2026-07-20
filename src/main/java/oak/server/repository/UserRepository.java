package oak.server.repository;

import oak.server.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
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

    boolean existsByUsername(String username);

    /** All users in username order — backs the public roster ({@code GET /api/users}). */
    List<User> findAllByOrderByUsernameAsc();
}
