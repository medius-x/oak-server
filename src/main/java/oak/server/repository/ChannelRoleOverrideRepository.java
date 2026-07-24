package oak.server.repository;

import oak.server.entity.ChannelRoleOverride;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChannelRoleOverrideRepository extends JpaRepository<ChannelRoleOverride, Long> {

    /** Every override on a channel — resolved per (user, channel) in {@code AuthorizationService}. */
    List<ChannelRoleOverride> findByChannelId(Long channelId);

    Optional<ChannelRoleOverride> findByChannelIdAndRoleId(Long channelId, Long roleId);
}
