package oak.server.repository;

import oak.server.entity.ChannelParticipant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * JPA repository for {@link ChannelParticipant} — DM / group-DM membership. The membership check
 * ({@link #isParticipant}) is on the STOMP hot path (DM subscription gating) and the REST hot path
 * (the DM branch in {@code AuthorizationService.hasInChannel}); it is a single indexed count.
 */
@Repository
public interface ChannelParticipantRepository extends JpaRepository<ChannelParticipant, Long> {

    boolean existsByChannelIdAndUserId(Long channelId, Long userId);

    /** Membership check by username — used by the DM subscription interceptor, where only the STOMP
     *  principal (a username) is available, not a user id. */
    @Query("select case when count(cp) > 0 then true else false end from ChannelParticipant cp "
            + "where cp.channel.id = :channelId and cp.user.username = :username")
    boolean isParticipant(@Param("channelId") Long channelId, @Param("username") String username);

    List<ChannelParticipant> findByChannelId(Long channelId);

    Optional<ChannelParticipant> findByChannelIdAndUserId(Long channelId, Long userId);

    /** Bulk pre-fetch of a user's DM-channel ids — lets the read-state snapshot resolve DM access in
     *  one query instead of one-per-channel. */
    @Query("select cp.channel.id from ChannelParticipant cp where cp.user.id = :userId")
    List<Long> findChannelIdsByUserId(@Param("userId") Long userId);

    long deleteByChannelIdAndUserId(Long channelId, Long userId);

    long deleteByChannelId(Long channelId);
}
