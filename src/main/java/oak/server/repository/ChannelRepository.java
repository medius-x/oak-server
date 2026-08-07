package oak.server.repository;

import oak.server.entity.Channel;
import oak.server.entity.ChannelType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ChannelRepository extends JpaRepository<Channel, Long> {

    List<Channel> findAllByOrderByIdAsc();

    /** The 1:1 DM channel for the given {@code dmKey}, if one exists (deduplication lookup). */
    Optional<Channel> findByDmKey(String dmKey);

    /** All channels whose type is <em>not</em> in {@code excluded} — backs the public channel list,
     *  which must hide {@code DM}/{@code GROUP_DM} channels. */
    List<Channel> findByTypeNotInOrderByIdAsc(Collection<ChannelType> excluded);
}
