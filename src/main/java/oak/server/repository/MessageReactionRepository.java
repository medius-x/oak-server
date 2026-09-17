package oak.server.repository;

import oak.server.entity.MessageReaction;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

public interface MessageReactionRepository extends JpaRepository<MessageReaction, Long> {

    /** User is eager-fetched: the snapshot aggregation reads username of every row, which on
     *  LAZY proxies would fire one extra select per reaction. */
    @EntityGraph(attributePaths = "user")
    List<MessageReaction> findByMessageId(Long messageId);

    /** Batch fetch for a history page — one query instead of a per-message N+1 (user eager for
     *  the same reason as {@link #findByMessageId}). */
    @EntityGraph(attributePaths = "user")
    List<MessageReaction> findByMessageIdIn(Collection<Long> messageIds);

    boolean existsByMessageIdAndUserIdAndReaction(Long messageId, Long userId, String reaction);

    /** Idempotent remove: deletes 0 or 1 row (unique message+user+reaction). */
    long deleteByMessageIdAndUserIdAndReaction(Long messageId, Long userId, String reaction);

    /** FK cleanup before a message delete (no ON DELETE CASCADE under ddl-auto=update). */
    @Transactional
    void deleteByMessageId(Long messageId);

    /** FK cleanup before a storage-GC batch delete. */
    @Transactional
    void deleteByMessageIdIn(Collection<Long> messageIds);
}
