package oak.server.repository;

import oak.server.entity.PollOption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/** JPA repository for {@link PollOption} (2026-11-25). */
@Repository
public interface PollOptionRepository extends JpaRepository<PollOption, Long> {

    List<PollOption> findByPollIdOrderByPositionAsc(Long pollId);

    /** Batch load for message pages — options of several polls in one query, position order. */
    List<PollOption> findByPollIdInOrderByPositionAsc(Collection<Long> pollIds);
}
