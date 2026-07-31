package oak.server.repository;

import oak.server.entity.Message;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface MessageRepository extends JpaRepository<Message, Long> {

    /** Most recent messages first; the service reverses the page into chronological order. */
    Page<Message> findByChannelIdOrderByIdDesc(Long channelId, Pageable pageable);

    /** Bulk-deletes every message in a channel — called before the channel itself is deleted so the
     *  {@code messages.channel_id} FK (no ON DELETE CASCADE under {@code ddl-auto=update}) doesn't
     *  reject the channel DELETE. Runs in the caller's transaction. */
    @Transactional
    void deleteByChannelId(Long channelId);
}
