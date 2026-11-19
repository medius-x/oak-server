package oak.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One continuous stretch of a user playing a PUBLISHED game (2026-11-19): the
 * roster activity transitions open and close these; a killed client's sessions
 * are closed by the {@code GameSessionService} sweeper once presence reports
 * the user gone. The achievements tier aggregates hours per game/genre from
 * here. No {@code @Data}/@ToString/@EqualsAndHashCode — JPA convention.
 */
@Entity
@Table(name = "game_sessions")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GameSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String username;

    /** The played game's IGDB id — a bare reference to {@link IgdbGame}. */
    @Column(name = "igdb_game_igdb_id", nullable = false)
    private Long igdbGameIgdbId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    /** Null while the session is OPEN. */
    @Column(name = "ended_at")
    private Instant endedAt;

    public GameSession(String username, Long igdbGameIgdbId, Instant startedAt) {
        this.username = username;
        this.igdbGameIgdbId = igdbGameIgdbId;
        this.startedAt = startedAt;
    }
}
