package oak.server.repository;

import oak.server.entity.Invite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * JPA repository for {@link Invite}. The single-use claim is an ATOMIC guarded DELETE —
 * a consumed link stops existing, so two concurrent registrations presenting the same
 * secret race in the database, exactly one wins, and the loser's INSERT rolls back with
 * its transaction (a repeat of a spent link reads as "unknown secret").
 *
 * <p>The {@code @Modifying} queries carry {@code clearAutomatically}/{@code flushAutomatically}:
 * a bulk statement bypasses the persistence context, and without the flags a
 * {@code @Transactional} test reading the same rows right after would see stale state
 * (the RBAC role-delete lesson).
 */
@Repository
public interface InviteRepository extends JpaRepository<Invite, Long> {

    Optional<Invite> findBySecret(String secret);

    /** Active invites (not revoked, not expired), newest first — the settings
     *  list. {@code join fetch createdBy}: every response carries the creator's name. */
    @Query("select i from Invite i join fetch i.createdBy "
            + "where i.revoked = false and i.expiresAt > :now "
            + "order by i.createdAt desc")
    List<Invite> findActive(@Param("now") Instant now);

    /** Atomically CONSUMES the secret (guarded DELETE); returns 0 when the invite is
     *  missing, already spent, revoked or expired — the caller turns that into a 403 and
     *  the transaction rolls back (the just-inserted user goes away with it). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Invite i where i.secret = :secret and i.revoked = false and i.expiresAt > :now")
    int claim(@Param("secret") String secret, @Param("now") Instant now);

    /** "Expire all": revokes every active invite; returns how many died. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Invite i set i.revoked = true where i.revoked = false and i.expiresAt > :now")
    int revokeAllActive(@Param("now") Instant now);

    /** The cleanup sweep: every row that can never become active again (revoked, or
     *  expired). Returns how many were removed. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Invite i where i.revoked = true or i.expiresAt <= :now")
    int deleteInactive(@Param("now") Instant now);
}
