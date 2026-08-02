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
}
