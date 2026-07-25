package oak.server.repository;

import oak.server.entity.ChannelRoleOverride;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChannelRoleOverrideRepository extends JpaRepository<ChannelRoleOverride, Long> {

    /** Every override on a channel — resolved per (user, channel) in {@code AuthorizationService}. */
    List<ChannelRoleOverride> findByChannelId(Long channelId);

    Optional<ChannelRoleOverride> findByChannelIdAndRoleId(Long channelId, Long roleId);

    /** Every override referencing a role — loaded (not bulk-deleted) so {@code RoleService} can
     *  {@code deleteAll} them, which keeps the persistence context consistent (a bulk JPQL delete
     *  would leave stale managed entities pointing at the role). */
    List<ChannelRoleOverride> findByRole_Id(Long roleId);
}
