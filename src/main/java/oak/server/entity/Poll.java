package oak.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A poll (2026-11-25) rides on ONE dedicated message: {@code content} stays null (the
 * attachment-only precedent) and the poll is the message's sole payload. Options and votes live
 * in their own tables ({@link PollOption}, {@link PollVote}); the aggregate is read through the
 * repositories, never through this entity's graph.
 *
 * <p>Lifecycle: an open poll accepts votes until {@code endsAt} (null = unlimited) or an early
 * close sets {@code closedAt}; both are checked lazily on every read plus the scheduled sweeper
 * that broadcasts the finish. A finished poll shows its final results to everyone.
 */
@Entity
@Table(name = "polls")
@Getter
@Setter
@NoArgsConstructor
public class Poll {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The poll's own message — UNIQUE, so the relation is 1:1 in both directions. */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "message_id", nullable = false)
    private Message message;

    @Column(nullable = false, length = 300)
    private String question;

    /** Multiple options may be selected per vote (checkboxes instead of a radio). */
    @Column(name = "allow_multiple", nullable = false)
    private boolean allowMultiple;

    /** Anonymous: results never carry WHO voted for what — only counts. */
    @Column(nullable = false)
    private boolean anonymous;

    /** When true a voter may re-vote (the whole selection is replaced); when false the first
     *  vote is final (user decision: the dialog's default is OFF). */
    @Column(name = "allow_vote_change", nullable = false)
    private boolean allowVoteChange;

    /** Vote deadline; null = unlimited. Checked lazily (the sweeper broadcasts the finish). */
    @Column(name = "ends_at")
    private Instant endsAt;

    /** Set when finished — by the deadline sweeper or an early close (author/moderation). */
    @Column(name = "closed_at")
    private Instant closedAt;
}
