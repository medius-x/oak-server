package oak.server.service.igdb;

import oak.server.dto.GameActivityChangedEvent;
import oak.server.dto.MyGameInfo;
import oak.server.dto.PresenceActivity;
import oak.server.entity.GameSession;
import oak.server.entity.IgdbGame;
import oak.server.repository.GameSessionRepository;
import oak.server.repository.IgdbGameRepository;
import oak.server.websocket.PresenceTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The play-session bookkeeping in isolation: a fake in-memory session
 * repository (the save() returns its argument), a mocked presence tracker and
 * a FIXED clock — the transitions (open on a game line, close on a manual
 * line, switch games, the killed-client sweeper) and the my-games aggregation
 * (closed + still-open sessions, newest-first) all drive off it.
 */
class GameSessionServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    private final GameSessionRepository sessions = mock(GameSessionRepository.class);
    private final IgdbGameRepository games = mock(IgdbGameRepository.class);
    private final PresenceTracker presence = mock(PresenceTracker.class);

    private final List<GameSession> store = new ArrayList<>();
    private GameSessionService service;

    @BeforeEach
    void setUp() {
        when(sessions.save(any(GameSession.class))).thenAnswer(call -> {
            GameSession saved = call.getArgument(0);
            if (saved.getId() == null) {
                saved.setId((long) (store.size() + 1)); // emulate the IDENTITY insert
                store.add(saved);
            }
            return saved;
        });
        when(sessions.findByUsername("alice")).thenAnswer(call ->
                store.stream().filter(row -> row.getUsername().equals("alice")).toList());
        when(sessions.findByUsernameAndEndedAtIsNull("alice")).thenAnswer(call ->
                store.stream().filter(row -> row.getUsername().equals("alice") && row.getEndedAt() == null).toList());
        when(sessions.findByEndedAtIsNull()).thenAnswer(call ->
                store.stream().filter(row -> row.getEndedAt() == null).toList());
        when(games.findById(1942L)).thenReturn(Optional.of(
                new IgdbGame(1942L, "The Witcher 3: Wild Hunt", "co1tiw", "[]", 1431993600L, NOW)));
        when(games.findById(1030L)).thenReturn(Optional.of(
                new IgdbGame(1030L, "ELDEN RING", null, "[]", 1645747200L, NOW)));
        // the virtual card a manual "playing" line mints (lowestManualId of the
        // empty mock returns no rows → the first manual id is always -1 here)
        when(games.findById(-1L)).thenReturn(Optional.of(
                new IgdbGame(-1L, "CS2", null, "[]", null, NOW)));
        service = new GameSessionService(sessions, games, presence,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void activity(Long igdbGameId, String name) {
        service.onActivityChanged(new GameActivityChangedEvent("alice",
                igdbGameId == null && name == null ? null
                        : new PresenceActivity("playing", name == null ? "game" : name, igdbGameId)));
    }

    @Test
    void aGameLineOpensASessionAndAClearClosesIt() {
        activity(1942L, "The Witcher 3: Wild Hunt");
        assertEquals(1, store.size());
        assertNull(store.get(0).getEndedAt());

        activity(null, null);
        assertEquals(NOW, store.get(0).getEndedAt());
    }

    @Test
    void aManualPlayingLineSessionsTooUnderAVirtualCard() {
        activity(1942L, "The Witcher 3: Wild Hunt");
        // a hand-written "playing CS2" carries no igdbGameId, but the time is
        // collectable (user decision 2026-11-19): a virtual NEGATIVE-id card
        service.onActivityChanged(new GameActivityChangedEvent("alice",
                new PresenceActivity("playing", "CS2", null)));
        assertEquals(2, store.size());
        assertEquals(NOW, store.get(0).getEndedAt());          // Witcher closed
        assertEquals(-1L, store.get(1).getIgdbGameIgdbId());   // the virtual card
        assertNull(store.get(1).getEndedAt());

        // the SAME manual name reuses the SAME virtual card (case-insensitive)
        service.onActivityChanged(new GameActivityChangedEvent("alice", null));
        service.onActivityChanged(new GameActivityChangedEvent("alice",
                new PresenceActivity("playing", "cs2", null)));
        assertEquals(-1L, store.get(2).getIgdbGameIgdbId());
    }

    @Test
    void myGamesServesTheManualRowWithoutMetadata() {
        service.onActivityChanged(new GameActivityChangedEvent("alice",
                new PresenceActivity("playing", "CS2", null)));
        List<MyGameInfo> mine = service.myGames("alice", 30);
        assertEquals(1, mine.size());
        assertEquals(-1L, mine.get(0).igdbId());
        assertEquals("CS2", mine.get(0).name());
        assertNull(mine.get(0).coverImageId());
    }

    @Test
    void nonPlayingManualKindsNeverSession() {
        // "listening" is not play time — it closes everything and opens nothing
        activity(1942L, "The Witcher 3: Wild Hunt");
        service.onActivityChanged(new GameActivityChangedEvent("alice",
                new PresenceActivity("listening", "radio", null)));
        assertEquals(1, store.size());
        assertEquals(NOW, store.get(0).getEndedAt());
    }

    @Test
    void switchingGamesClosesTheOldOneAndOpensTheNew() {
        activity(1942L, "The Witcher 3: Wild Hunt");
        activity(1030L, "ELDEN RING");
        assertEquals(2, store.size());
        assertEquals(NOW, store.get(0).getEndedAt()); // Witcher closed
        assertNull(store.get(1).getEndedAt());        // Elden Ring open
    }

    @Test
    void theSweeperClosesSessionsOfUsersPresenceReportsGone() {
        activity(1942L, "The Witcher 3: Wild Hunt");
        when(presence.isOnline("alice")).thenReturn(false);
        service.sweepStale();
        assertEquals(NOW, store.get(0).getEndedAt());

        // an online user's session keeps running (a 5-hour game is legitimate)
        store.get(0).setEndedAt(null);
        when(presence.isOnline("alice")).thenReturn(true);
        service.sweepStale();
        assertNull(store.get(0).getEndedAt());
    }

    @Test
    void myGamesSumsClosedAndOpenSessionsNewestFirst() {
        // Witcher: 2 hours yesterday + 30 minutes open right now
        store.add(new GameSession("alice", 1942L, NOW.minus(Duration.ofDays(1))));
        store.get(0).setEndedAt(NOW.minus(Duration.ofDays(1)).plus(Duration.ofHours(2)));
        store.add(new GameSession("alice", 1942L, NOW.minus(Duration.ofMinutes(30))));
        // Elden Ring: 45 minutes yesterday evening
        store.add(new GameSession("alice", 1030L, NOW.minus(Duration.ofHours(14))));
        store.get(2).setEndedAt(NOW.minus(Duration.ofHours(14)).plus(Duration.ofMinutes(45)));

        List<MyGameInfo> mine = service.myGames("alice", 30);
        assertEquals(2, mine.size());
        // newest by last played: the OPEN Witcher session (30 min ago) outranks
        // yesterday evening's Elden Ring
        assertEquals(1942L, mine.get(0).igdbId());
        assertEquals(2 * 3600 + 30 * 60, mine.get(0).totalSeconds()); // closed + open-up-to-now
        assertEquals("The Witcher 3: Wild Hunt", mine.get(0).name());
        assertEquals(1030L, mine.get(1).igdbId());
        assertEquals(45 * 60, mine.get(1).totalSeconds());
    }
}
