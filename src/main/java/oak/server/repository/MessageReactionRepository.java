package oak.server.repository;

import oak.server.entity.MessageReaction;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /** Count of DISTINCT reaction values on a message (the add-cap check). Explicit JPQL, not a
     *  derived name: {@code countDistinctByMessageId} would count distinct ENTITIES (== row
     *  count), not distinct reaction strings. */
    @Query("select count(distinct r.reaction) from MessageReaction r where r.message.id = :messageId")
    long countDistinctReactionByMessageId(@Param("messageId") Long messageId);

    /** Idempotent remove: deletes 0 or 1 row (unique message+user+reaction). */
    long deleteByMessageIdAndUserIdAndReaction(Long messageId, Long userId, String reaction);

    /** FK cleanup before a message delete (no ON DELETE CASCADE under ddl-auto=update). */
    @Transactional
    void deleteByMessageId(Long messageId);

    /** FK cleanup before a storage-GC batch delete. */
    @Transactional
    void deleteByMessageIdIn(Collection<Long> messageIds);

    /** FK cleanup before a whole CHANNEL's messages are bulk-deleted (channel deletion, DM close,
     *  discussion close) — the message_id FK has no ON DELETE CASCADE, so reactions must go first.
     *  JPQL bulk delete (not derived): the filter runs through the reaction's message association;
     *  {@code @Modifying} is what makes it execute as an update (a bare @Query delete runs as a
     *  select and blows up at runtime). */
    @org.springframework.data.jpa.repository.Query("delete from MessageReaction r where r.message.channel.id = :channelId")
    @org.springframework.data.jpa.repository.Modifying
    @Transactional
    void deleteByChannelId(@org.springframework.data.repository.query.Param("channelId") Long channelId);
}
