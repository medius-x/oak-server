package oak.server.service.igdb;

import lombok.extern.slf4j.Slf4j;
import oak.server.dto.GameActivityChangedEvent;
import oak.server.dto.GameCard;
import oak.server.dto.MyGameInfo;
import oak.server.entity.GameSession;
import oak.server.entity.IgdbGame;
import oak.server.repository.GameSessionRepository;
import oak.server.repository.IgdbGameRepository;
import oak.server.websocket.PresenceTracker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The play-session bookkeeping (the achievements foundation, 2026-11-19):
 * every DERIVED roster-activity move (a {@link GameActivityChangedEvent} from
 * the presence tracker — including dampening-swallowed transitions) opens and
 * closes {@link GameSession} rows. A line WITHOUT an {@code igdbGameId} (a
 * manual rule, "listening to…") closes everything — only PUBLISHED game lines
 * accumulate time, so a game the user hid never sessions at all (privacy and
 * the "no stats while hidden" rule by construction — there is deliberately NO
 * separate statistics report).
 *
 * <p>Killed clients leave open rows behind; the once-a-minute sweeper closes
 * the sessions of everyone presence no longer reports online (a live client
 * keeps its user online through any STOMP session, invisible included).
 */
@Slf4j
@Service
public class GameSessionService {

    private final GameSessionRepository sessions;
    private final IgdbGameRepository games;
    private final PresenceTracker presence;
    private final Clock clock;

    @Autowired
    public GameSessionService(GameSessionRepository sessions, IgdbGameRepository games,
                              PresenceTracker presence) {
        this(sessions, games, presence, Clock.systemUTC());
    }

    /** Test seam: the clock drives started/ended stamps. */
    GameSessionService(GameSessionRepository sessions, IgdbGameRepository games,
                       PresenceTracker presence, Clock clock) {
        this.sessions = sessions;
        this.games = games;
        this.presence = presence;
        this.clock = clock;
    }

    /** A derived activity move: close what no longer matches, open the new game.
     *  A DETECTED game line carries its IGDB id; a MANUAL "playing …" line has
     *  none but sessions all the same (user decision 2026-11-19: a manual
     *  playing activity differs from a detected one only by missing metadata —
     *  the time is collectable) — its name gets a virtual card under a
     *  NEGATIVE id. Every other kind ("listening", a clear) closes sessions. */
    @EventListener
    public synchronized void onActivityChanged(GameActivityChangedEvent event) {
        String username = event.username();
        Long gameIgdbId = event.activity() == null ? null : event.activity().igdbGameId();
        if (gameIgdbId == null && event.activity() != null
                && "playing".equals(event.activity().type()) && event.activity().name() != null) {
            gameIgdbId = manualGameId(event.activity().name());
        }
        List<GameSession> open = sessions.findByUsernameAndEndedAtIsNull(username);
        boolean alreadyOpen = false;
        for (GameSession session : open) {
            if (gameIgdbId != null && gameIgdbId.equals(session.getIgdbGameIgdbId())) {
                alreadyOpen = true;
            } else {
                close(session);
            }
        }
        if (gameIgdbId != null && !alreadyOpen) {
            sessions.save(new GameSession(username, gameIgdbId, now()));
            log.debug("game session opened: {} -> game #{}", username, gameIgdbId);
        }
    }

    /** The virtual card id for a MANUAL activity name: one shared row per name
     *  (case-insensitive), minted under the current lowest negative id — real
     *  IGDB ids are strictly positive, so the spaces never meet. */
    private Long manualGameId(String name) {
        return games.findFirstByIgdbIdLessThanAndNameIgnoreCase(0L, name)
                .map(IgdbGame::getIgdbId)
                .orElseGet(() -> {
                    long id = games.lowestManualId().orElse(0L) - 1;
                    games.save(new IgdbGame(id, name.trim(), null, "[]", null, now()));
                    log.info("manual game card minted: #{} \"{}\"", id, name.trim());
                    return id;
                });
    }

    /** Closes open sessions of users presence no longer reports (a killed client). */
    @Scheduled(fixedRate = 60_000)
    public void sweepStale() {
        for (GameSession session : sessions.findByEndedAtIsNull()) {
            if (!presence.isOnline(session.getUsername())) {
                close(session);
                log.debug("game session swept (user gone): {} -> IGDB #{}",
                        session.getUsername(), session.getIgdbGameIgdbId());
            }
        }
    }

    private void close(GameSession session) {
        session.setEndedAt(now());
        sessions.save(session);
    }

    /**
     * The manual-hours merge (the manual-rule IGDB binding, 2026-11-25): moves
     * the CALLER's sessions off the virtual manual cards onto a real game.
     * Match key: the chosen GAME's normalized name (NOT the rule's text — a
     * rule may be re-created under a different label while the hours live here,
     * on the server); exact equality only, deliberately no fuzzy matching (a
     * wrong merge is unmergeable). Only the CALLER's rows move — another user
     * gaming on the same shared virtual card keeps theirs; a card left with no
     * sessions at all disappears from the list.
     */
    @org.springframework.transaction.annotation.Transactional
    public int mergeManualSessions(String username, long toIgdbId) {
        IgdbGame target = games.findById(toIgdbId).orElse(null);
        if (target == null || target.getIgdbId() <= 0) {
            throw new IllegalArgumentException("Unknown game");
        }
        String targetName = IgdbNames.normalizeTitle(target.getName());
        if (targetName == null) {
            throw new IllegalArgumentException("Unnamed game");
        }
        int moved = 0;
        for (IgdbGame card : games.findByIgdbIdLessThan(0)) {
            if (!targetName.equals(IgdbNames.normalizeTitle(card.getName()))) {
                continue;
            }
            moved += sessions.moveSessions(username, card.getIgdbId(), toIgdbId);
            if (!sessions.existsByIgdbGameIgdbId(card.getIgdbId())) {
                games.delete(card); // nobody's hours reference it anymore
                log.info("manual game card #{} \"{}\" emptied and removed by the merge", card.getIgdbId(), card.getName());
            }
        }
        if (moved > 0) {
            log.info("merged {} manual session row(s) of {} into IGDB #{}", moved, username, toIgdbId);
        }
        return moved;
    }

    /** The user's play history aggregate (newest first, capped): card + summed
     *  playtime (an open session counts up to now) + last played. Games whose
     *  card left the cache are skipped — sessions only ever open for resolved
     *  cards, so this is a wiped-table edge, not a live path. */
    public List<MyGameInfo> myGames(String username, int cap) {
        Instant now = now();
        record Aggregate(long seconds, Instant lastPlayed) {
        }
        Map<Long, Aggregate> byGame = new HashMap<>();
        for (GameSession session : sessions.findByUsername(username)) {
            Instant end = session.getEndedAt() == null ? now : session.getEndedAt();
            long seconds = Math.max(0, Duration.between(session.getStartedAt(), end).toSeconds());
            Aggregate current = byGame.get(session.getIgdbGameIgdbId());
            byGame.put(session.getIgdbGameIgdbId(), new Aggregate(
                    (current == null ? 0 : current.seconds()) + seconds,
                    current == null || session.getStartedAt().isAfter(current.lastPlayed())
                            ? session.getStartedAt() : current.lastPlayed()));
        }
        List<MyGameInfo> out = new ArrayList<>();
        byGame.entrySet().stream()
                .sorted((left, right) -> right.getValue().lastPlayed().compareTo(left.getValue().lastPlayed()))
                .limit(cap)
                .forEach(entry -> games.findById(entry.getKey()).ifPresent(game -> {
                    GameCard card = GameCard.from(game);
                    out.add(new MyGameInfo(card.igdbId(), card.name(), card.coverImageId(),
                            card.genres(), entry.getValue().seconds(), entry.getValue().lastPlayed()));
                }));
        return out;
    }

    private Instant now() {
        return Instant.now(clock);
    }
}
