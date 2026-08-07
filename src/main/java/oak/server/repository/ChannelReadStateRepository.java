package oak.server.repository;

import oak.server.entity.ChannelReadState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChannelReadStateRepository extends JpaRepository<ChannelReadState, Long> {

    /** The single read pointer for (user, channel), absent when the user has never read the channel. */
    Optional<ChannelReadState> findByUserIdAndChannelId(Long userId, Long channelId);

    /** All read pointers for a user (one snapshot). */
    List<ChannelReadState> findByUserId(Long userId);

    /** Derived bulk delete of every read pointer for a channel — used when a DM / channel is closed,
     *  because {@code channel_read_states.channel_id} has no ON DELETE CASCADE under ddl-auto=update. */
    long deleteByChannelId(Long channelId);
}
