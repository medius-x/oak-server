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

    /** The same public-channel set, in the manual sidebar order — {@code (position, id)}
     *  (the list contract since categories). */
    List<Channel> findByTypeNotInOrderByPositionAscIdAsc(Collection<ChannelType> excluded);

    /** The current tail of the sidebar order — creations append {@code max(position) + 1}. */
    Channel findTopByOrderByPositionDesc();

    /** Whether any channel still groups under the given category (the non-empty
     *  deletion gate). */
    boolean existsByParentId(Long parentId);
}
