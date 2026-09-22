package oak.server.repository;

import oak.server.entity.Message;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MessageRepository extends JpaRepository<Message, Long> {

    /** Most recent messages first; the service reverses the page into chronological order. */
    Page<Message> findByChannelIdOrderByIdDesc(Long channelId, Pageable pageable);

    /** History cursor: the page of messages STRICTLY OLDER than {@code beforeId}, newest first
     *  (the service reverses into chronological order as usual). Backs {@code ?beforeId=} — the
     *  client pages backwards one hop at a time (e.g. when jumping to an old reply target). */
    Page<Message> findByChannelIdAndIdLessThanOrderByIdDesc(Long channelId, Long beforeId, Pageable pageable);

    /** One message scoped to its channel — a message id that lives in another channel 404s, so
     *  {@code /api/channels/{otherChannel}/messages/{id}} cannot reach it (no cross-channel leak). */
    Optional<Message> findByIdAndChannelId(Long id, Long channelId);

    /** The OLDEST message ids (storage-GC purge batches walk the table from the start of time),
     *  EXCLUDING pinned rows — a pin is exempt from the nightly purge (the whole point of
     *  pinning: "important" must not silently vanish when the disk fills). Explicit JPQL: the
     *  derived spelling parsed crooked and converted entities, not ids. */
    @Query("select m.id from Message m where m.pinned = false order by m.id asc")
    List<Long> findIdsOldestFirst(Pageable pageable);

    /** A channel's pinned messages, oldest pin first — the wire list clients render newest-last
     *  (the pin card shows the LAST element = the latest pin). */
    List<Message> findByChannelIdAndPinnedTrueOrderByPinnedAtAsc(Long channelId);

    /** Pin count for the per-channel cap (MessageService.MAX_PINS_PER_CHANNEL). */
    long countByChannelIdAndPinnedTrue(Long channelId);

    /** Unread count for a channel relative to a read floor (the user's {@code lastReadMessageId};
     *  pass 0 to count everything when nothing has been read yet). */
    long countByChannelIdAndIdGreaterThan(Long channelId, Long id);

    /** Total message count of a channel — the discussion reply count. */
    long countByChannelId(Long channelId);

    /** Every message row of a channel (id-ascending) — the discussion teardown collects the
     *  attachment keys before the bulk delete. Deliberately unpaged: a thread's messages are
     *  bounded in practice, and the callers need them all. */
    List<Message> findByChannelId(Long channelId);

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

    /** The page of messages STRICTLY NEWER than {@code afterId}, oldest first — the client's
     *  downward pagination away from an {@code aroundId} window (no service-side reverse
     *  needed; {@code Pageable} carries the page size). */
    List<Message> findByChannelIdAndIdGreaterThanOrderByIdAsc(Long channelId, Long afterId, Pageable pageable);

    /** Search page: substring match on the content OR the attachments JSON (so a file name
     *  matches), newest first, strictly older than the cursor. Native SQL because of ILIKE and
     *  the IN-list. The non-null sentinels ({@code authorId = 0} = no filter,
     *  {@code beforeId = Long.MAX_VALUE} = no cursor) keep ONE query shape for every
     *  combination — NULL parameter typing in native queries is unreliable across PG/H2.
     *  COALESCE wraps both text columns so attachment-only rows (content NULL) survive an
     *  author-only search ({@code NULL ILIKE} is NULL = no match without it). */
    @Query(value = "SELECT * FROM messages WHERE channel_id IN (:channelIds)"
            + " AND (:authorId = 0 OR author_id = :authorId)"
            + " AND (COALESCE(content, '') ILIKE :pattern OR COALESCE(attachments, '') ILIKE :pattern)"
            + " AND id < :beforeId ORDER BY id DESC LIMIT :limit", nativeQuery = true)
    List<Message> searchMessages(@Param("channelIds") Collection<Long> channelIds,
                                 @Param("authorId") long authorId,
                                 @Param("pattern") String pattern,
                                 @Param("beforeId") long beforeId,
                                 @Param("limit") int limit);

    /** Total hit count for the same filter (WITHOUT the cursor — the page counter stays
     *  constant while the client pages through results). Same sentinels as
     *  {@link #searchMessages}. */
    @Query(value = "SELECT COUNT(*) FROM messages WHERE channel_id IN (:channelIds)"
            + " AND (:authorId = 0 OR author_id = :authorId)"
            + " AND (COALESCE(content, '') ILIKE :pattern OR COALESCE(attachments, '') ILIKE :pattern)",
            nativeQuery = true)
    long countSearchMessages(@Param("channelIds") Collection<Long> channelIds,
                             @Param("authorId") long authorId,
                             @Param("pattern") String pattern);

    /** Messages whose attachment list (the JSON-text {@code attachments} column) contains the
     *  pattern. Native SQL because JPQL sees the converter type ({@code List<AttachmentRef>}),
     *  not the stored text; keys embed a random UUID, so a {@code LIKE %key%} hit is a real
     *  reference, never a substring of ANOTHER key (a {@code _} in a file name can only cause a
     *  harmless false positive that later 404s at MinIO). */
    @Query(value = "SELECT COUNT(*) FROM messages WHERE attachments LIKE :pattern", nativeQuery = true)
    long countByAttachmentsTextLike(@Param("pattern") String pattern);
}
