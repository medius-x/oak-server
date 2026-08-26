package oak.server.repository;

import oak.server.entity.ChannelUserOverride;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface ChannelUserOverrideRepository extends JpaRepository<ChannelUserOverride, Long> {

    /** Every user-level override on a channel — resolved per (user, channel) in
     *  {@code AuthorizationService} (the top resolution layer). */
    List<ChannelUserOverride> findByChannelId(Long channelId);

    /** Same data as {@link #findByChannelId(Long)} but with the {@code user} association eager —
     *  the REST listing needs each row's username without an N+1. Named {@code findWithUserBy...}
     *  (a {@code findByChannelIdWithUser} derived name does not parse — Spring reads the
     *  {@code WithUser} suffix as part of the property path). */
    @EntityGraph(value = "ChannelUserOverride.withUser")
    List<ChannelUserOverride> findWithUserByChannelId(Long channelId);

    Optional<ChannelUserOverride> findByChannelIdAndUserId(Long channelId, Long userId);

    /** Bulk-deletes every user override on a channel — called before the channel itself is deleted
     *  so the {@code channel_user_overrides.channel_id} FK doesn't reject the channel DELETE. Runs
     *  in the caller's transaction. */
    @Transactional
    void deleteByChannelId(Long channelId);
}
