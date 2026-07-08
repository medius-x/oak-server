package oak.server.repository;

import oak.server.entity.Message;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageRepository extends JpaRepository<Message, Long> {

    /** Most recent messages first; the service reverses the page into chronological order. */
    Page<Message> findByChannelIdOrderByIdDesc(Long channelId, Pageable pageable);
}
