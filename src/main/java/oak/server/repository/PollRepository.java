package oak.server.repository;

import oak.server.entity.Poll;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** JPA repository for {@link Poll} (2026-11-25). */
@Repository
public interface PollRepository extends JpaRepository<Poll, Long> {

    Optional<Poll> findByMessageId(Long messageId);

    /** Batch load for message pages — one query, keyed by message id. */
    List<Poll> findByMessageIdIn(Collection<Long> messageIds);

    /** The sweeper's window: still open, deadline already passed. */
    List<Poll> findByClosedAtIsNullAndEndsAtLessThanEqual(Instant now);

    /** The poll of a message when the caller only needs to know there IS one (the edit gate). */
    @Query("select (count(p) > 0) from Poll p where p.message.id = :messageId")
    boolean existsByMessageId(@Param("messageId") Long messageId);
}
