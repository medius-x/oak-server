package oak.server.repository;

import oak.server.entity.PollVote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * JPA repository for {@link PollVote} (2026-11-25). The {@code @Modifying} delete carries the
 * {@code clearAutomatically}/{@code flushAutomatically} discipline — a bulk statement bypasses
 * the persistence context (the RBAC role-delete lesson).
 */
@Repository
public interface PollVoteRepository extends JpaRepository<PollVote, Long> {

    /** The poll's whole vote set (the tally source). */
    List<PollVote> findByPollId(Long pollId);

    /** The voter's current rows in one poll (empty = has not voted; several = multi-choice). */
    List<PollVote> findByPollIdAndUsername(Long pollId, String username);

    /** The voter's whole footprint in a CHANNEL's polls — the client's
     *  "already voted" snapshot at channel open (survives client restarts). */
    List<PollVote> findByPollMessageChannelIdAndUsername(Long channelId, String username);

    /** A re-vote's replace step: the voter's whole selection goes, then the new set lands. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from PollVote v where v.poll.id = :pollId and v.username = :username")
    int deleteByPollIdAndUsername(@Param("pollId") Long pollId, @Param("username") String username);

    /** The poll's headline number: DISTINCT people, not raw rows (a multi-choice voter counts once). */
    @Query("select count(distinct v.username) from PollVote v where v.poll.id = :pollId")
    int countVoters(@Param("pollId") Long pollId);

    /** The message-page batch of the headline numbers (the history snapshot fix,
     *  2026-11-25): DISTINCT people per poll id, one query for the whole page. */
    @Query("select v.poll.id, count(distinct v.username) from PollVote v where v.poll.id in :pollIds group by v.poll.id")
    List<Object[]> countVotersByPollIdIn(@Param("pollIds") java.util.Collection<Long> pollIds);
}
