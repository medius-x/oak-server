package oak.server.repository;

import oak.server.entity.Message;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface MessageRepository extends JpaRepository<Message, Long> {

    /** Most recent messages first; the service reverses the page into chronological order. */
    Page<Message> findByChannelIdOrderByIdDesc(Long channelId, Pageable pageable);

    /** Unread count for a channel relative to a read floor (the user's {@code lastReadMessageId};
     *  pass 0 to count everything when nothing has been read yet). */
    long countByChannelIdAndIdGreaterThan(Long channelId, Long id);

    /** The newest message id in a channel (the ack fallback when the client omits the id, and the
     *  {@code lastMessageId} reported in a read-state snapshot). */
    Optional<Message> findTopByChannelIdOrderByIdDesc(Long channelId);

    /** The freshest {@code limit} unread messages (id &gt; readFloor), newest first — capped to keep
     *  mention-count parsing bounded. Older unread mentions beyond the window are not counted. */
    List<Message> findTop200ByChannelIdAndIdGreaterThanOrderByIdDesc(Long channelId, Long id);

    /** Bulk-deletes every message in a channel — called before the channel itself is deleted so the
     *  {@code messages.channel_id} FK (no ON DELETE CASCADE under {@code ddl-auto=update}) doesn't
     *  reject the channel DELETE. Runs in the caller's transaction. */
    @Transactional
    void deleteByChannelId(Long channelId);
}
