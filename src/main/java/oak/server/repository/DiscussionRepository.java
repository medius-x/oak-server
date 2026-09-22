package oak.server.repository;

import oak.server.entity.Discussion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DiscussionRepository extends JpaRepository<Discussion, Long> {

    /** One thread per message (the unique origin_message_id) — the duplicate-create check. */
    Optional<Discussion> findByOriginMessageId(Long originMessageId);

    /** Resolve a DISCUSSION channel's companion row (1:1 with the channel). */
    Optional<Discussion> findByChannelId(Long channelId);

    /** Every discussion under one parent channel — the channel-scoped listing and the
     *  parent-deletion teardown. */
    List<Discussion> findByParentChannelId(Long parentChannelId);

    /** Batch resolve for the viewer's tracked discussions (participant channel ids in). */
    List<Discussion> findByChannelIdIn(Collection<Long> channelIds);
}
