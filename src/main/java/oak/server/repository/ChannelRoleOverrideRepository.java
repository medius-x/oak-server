package oak.server.repository;

import oak.server.entity.ChannelRoleOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface ChannelRoleOverrideRepository extends JpaRepository<ChannelRoleOverride, Long> {

    /** Every override on a channel — resolved per (user, channel) in {@code AuthorizationService}. */
    List<ChannelRoleOverride> findByChannelId(Long channelId);

    Optional<ChannelRoleOverride> findByChannelIdAndRoleId(Long channelId, Long roleId);

    /** Bulk-deletes every override on a channel — called before the channel itself is deleted so the
     *  {@code channel_role_overrides.channel_id} FK doesn't reject the channel DELETE. Runs in the
     *  caller's transaction. */
    @Transactional
    void deleteByChannelId(Long channelId);

    /** Every override referencing a role — loaded (not bulk-deleted) so {@code RoleService} can
     *  {@code deleteAll} them, which keeps the persistence context consistent (a bulk JPQL delete
     *  would leave stale managed entities pointing at the role). */
    List<ChannelRoleOverride> findByRole_Id(Long roleId);
}
