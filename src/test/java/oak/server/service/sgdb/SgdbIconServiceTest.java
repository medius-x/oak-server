package oak.server.service.sgdb;

import oak.server.entity.IgdbGame;
import oak.server.entity.ServerProfile;
import oak.server.repository.IgdbGameRepository;
import oak.server.repository.IgdbMatchCacheRepository;
import oak.server.repository.ServerProfileRepository;
import oak.server.service.igdb.IgdbException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The icon lane's queue and resolve policy, all collaborators mocked (the lane
 * itself is the only thread — the assertions wait for it briefly). Covers: the
 * backfill recovers the appid from the match cache and SAVES the icon; a card
 * that already has one is never re-asked; an upstream failure leaves the card
 * as-is; manual (negative-id) cards are out of scope entirely.
 */
class SgdbIconServiceTest {

    private final ServerProfileRepository profiles = mock(ServerProfileRepository.class);
    private final IgdbGameRepository games = mock(IgdbGameRepository.class);
    private final IgdbMatchCacheRepository matches = mock(IgdbMatchCacheRepository.class);
    private final SgdbClient client = mock(SgdbClient.class);
    private final SgdbIconService service = new SgdbIconService(profiles, games, matches, client);

    private static ServerProfile profileWithKey() {
        ServerProfile profile = new ServerProfile("OAK");
        profile.setSgdbApiKey("k1");
        return profile;
    }

    private static IgdbGame card(long igdbId) {
        return new IgdbGame(igdbId, "Game", null, "[]", null, Instant.EPOCH);
    }

    @Test
    void backfillRecoversTheAppidFromTheMatchCacheAndSavesTheIcon() throws Exception {
        when(profiles.singleton()).thenReturn(profileWithKey());
        IgdbGame game = card(1942);
        when(games.findById(1942L)).thenReturn(Optional.of(game));
        when(matches.findSteamKeys(1942L)).thenReturn(List.of("steam:292030"));
        when(client.bestOfficialIcon("k1", "292030"))
                .thenReturn(new SgdbClient.SgdbIcon("https://cdn/icon.png", "image/png", new byte[]{1, 2}));

        service.queueBackfill(1942);
        assertTrue(laneSettled());
        verify(games).save(game);
        assertEquals("https://cdn/icon.png", game.getSgdbIconUrl());
        assertEquals("image/png", game.getSgdbIconMime());
        org.junit.jupiter.api.Assertions.assertArrayEquals(new byte[]{1, 2}, game.getSgdbIconBytes());
    }

    @Test
    void aCardThatAlreadyHasAnIconIsNeverReAsked() throws Exception {
        when(profiles.singleton()).thenReturn(profileWithKey());
        IgdbGame resolved = card(1942);
        resolved.setSgdbIconBytes(new byte[]{7}); // the resolved marker is the BYTES
        when(games.findById(1942L)).thenReturn(Optional.of(resolved));

        service.queueBackfill(1942);
        assertTrue(laneSettled());
        verify(client, never()).bestOfficialIcon(anyString(), anyString());
        verify(games, never()).save(any(IgdbGame.class));
    }

    @Test
    void anUpstreamFailureLeavesTheCardUntouched() throws Exception {
        when(profiles.singleton()).thenReturn(profileWithKey());
        IgdbGame game = card(1942);
        when(games.findById(1942L)).thenReturn(Optional.of(game));
        when(matches.findSteamKeys(1942L)).thenReturn(List.of("steam:292030"));
        when(client.bestOfficialIcon("k1", "292030")).thenThrow(new IgdbException("parked"));

        service.queueBackfill(1942);
        assertTrue(laneSettled());
        assertNull(game.getSgdbIconUrl());
        verify(games, never()).save(any(IgdbGame.class));
    }

    @Test
    void aGameNoSteamKeyEverPointedAtAndManualCardsQueueNothing() throws Exception {
        when(profiles.singleton()).thenReturn(profileWithKey());
        when(games.findById(1942L)).thenReturn(Optional.of(card(1942)));
        when(matches.findSteamKeys(1942L)).thenReturn(List.of());

        service.queueBackfill(1942);
        service.queueBackfill(-1L); // a manual card — no IGDB identity at all
        assertTrue(laneSettled());
        verify(client, never()).bestOfficialIcon(anyString(), anyString());
    }

    @Test
    void withoutAKeyNothingIsEverQueued() throws Exception {
        when(profiles.singleton()).thenReturn(new ServerProfile("OAK")); // no key
        service.queueForAppId(1942, "292030");
        assertTrue(laneSettled());
        verify(games, never()).findById(anyLong());
    }

    @Test
    void theStuckLaneWatchStaysQuietWithinBudgetAndNamesTheGamePastIt() {
        long startedAtMillis = 1_000_000L;
        // one second of business — nothing to say
        assertNull(SgdbIconService.stuckLaneLine(startedAtMillis, startedAtMillis + 1_000L, 1942));
        // ten minutes minus a hair — the last quiet tick
        assertNull(SgdbIconService.stuckLaneLine(startedAtMillis,
                startedAtMillis + SgdbIconService.LANE_STUCK_AFTER_MILLIS - 1, 1942));
        // past the budget — the game and the age must both ride the line
        String line = SgdbIconService.stuckLaneLine(startedAtMillis,
                startedAtMillis + SgdbIconService.LANE_STUCK_AFTER_MILLIS + 5_000L, 1942);
        assertTrue(line.contains("1942"));
        assertTrue(line.contains("parked"));
    }

    /** The single daemon lane drains fast (no I/O — everything is mocked). */
    private static boolean laneSettled() throws InterruptedException {
        Thread.sleep(50);
        return true;
    }
}
