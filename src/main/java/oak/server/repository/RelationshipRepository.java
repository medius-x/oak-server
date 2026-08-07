package oak.server.repository;

import oak.server.entity.Relationship;
import oak.server.entity.RelationshipType;
import oak.server.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * JPA repository for {@link Relationship}. Derived queries cover the social-graph operations:
 * listing my edges, finding a specific directed edge, and bulk-deleting an edge in either direction.
 */
@Repository
public interface RelationshipRepository extends JpaRepository<Relationship, Long> {

    /** Every edge where I am the source (friends I have, blocks I placed, requests I sent). */
    List<Relationship> findBySourceOrderByCreatedAtDesc(User source);

    List<Relationship> findBySourceAndType(User source, RelationshipType type);

    /** Edges where I am the target of the given type — used for incoming pending requests. */
    List<Relationship> findByTargetAndType(User target, RelationshipType type);

    Optional<Relationship> findBySourceAndTargetAndType(User source, User target, RelationshipType type);

    /** Derived delete — runs within the caller's {@code @Transactional}. Returns rows deleted. */
    long deleteBySourceAndTargetAndType(User source, User target, RelationshipType type);

    /** Whether either user has blocked the other (blocks are directed, but a block either way prevents
     *  friend requests and DMs). */
    @Query("select case when count(r) > 0 then true else false end from Relationship r "
            + "where r.type = oak.server.entity.RelationshipType.BLOCKED "
            + "and ((r.source = :first and r.target = :second) "
            + "or (r.source = :second and r.target = :first))")
    boolean blockedEitherDirection(@Param("first") User first, @Param("second") User second);
}
