package oak.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One (voter, option) pair of a {@link Poll}. A single-choice poll holds one row per voter, a
 * multi-choice poll several; a re-vote (allowed polls only) DELETES the voter's whole set and
 * inserts the new one. Uniqueness (poll, option, username) is enforced by the schema — the same
 * pair can never land twice.
 */
@Entity
@Table(name = "poll_votes")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PollVote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "poll_id", nullable = false)
    private Poll poll;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "option_id", nullable = false)
    private PollOption option;

    /** The voter — keyed by username (the game_sessions pattern: names are never reused). */
    @Column(nullable = false)
    private String username;

    @Column(name = "voted_at", nullable = false)
    private Instant votedAt;

    public PollVote(Poll poll, PollOption option, String username, Instant votedAt) {
        this.poll = poll;
        this.option = option;
        this.username = username;
        this.votedAt = votedAt;
    }
}
