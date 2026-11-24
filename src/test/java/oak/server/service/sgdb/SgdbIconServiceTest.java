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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The icon lane's queue and resolve policy, all collaborators mocked (the lane
 * itself is the only thread — the assertions wait for it briefly). Covers:
 * the backfill recovers the appid from the match cache and STORES the icon
 * atomically; a burst attempt's PARTIAL is persisted for the Range resume; a
 * card that already has one is never re-asked; an upstream failure leaves the
 * card as-is; manual (negative-id) cards are out of scope entirely; and the
 * CLIENT-offer acceptance (first-wins, magic-sniffed).
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

    private static final byte[] PNG_BYTES = new byte[]{
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2};

    @Test
    void backfillRecoversTheAppidAndStoresTheIconAtomically() throws Exception {
        when(profiles.singleton()).thenReturn(profileWithKey());
        IgdbGame game = card(1942);
        when(games.findById(1942L)).thenReturn(Optional.of(game));
        when(matches.findSteamKeys(1942L)).thenReturn(List.of("steam:292030"));
        when(client.resolveIcon(eq("k1"), eq("292030"), isNull(), isNull(), any()))
                .thenReturn(SgdbClient.Resolution.done(
                        new SgdbClient.SgdbIcon("https://cdn/icon.png", "image/png", new byte[]{1, 2})));
        when(games.storeIconIfAbsent(1942L, "https://cdn/icon.png", "image/png", new byte[]{1, 2}))
                .thenReturn(1);

        service.queueBackfill(1942);
        assertTrue(laneSettled());

        verify(games).storeIconIfAbsent(1942L, "https://cdn/icon.png", "image/png", new byte[]{1, 2});
    }

    @Test
    void aLosingRacesCopyIsDroppedSilently() throws Exception {
        // a client offer won mid-attempt: the atomic UPDATE answers 0 — our
        // bytes are discarded with a log line, no overwrite, no error
        when(profiles.singleton()).thenReturn(profileWithKey());
        IgdbGame game = card(1942);
        when(games.findById(1942L)).thenReturn(Optional.of(game));
        when(matches.findSteamKeys(1942L)).thenReturn(List.of("steam:292030"));
        when(client.resolveIcon(eq("k1"), eq("292030"), any(), any(), any()))
                .thenReturn(SgdbClient.Resolution.done(
                        new SgdbClient.SgdbIcon("https://cdn/icon.png", "image/png", new byte[]{1})));
        when(games.storeIconIfAbsent(anyLong(), anyString(), anyString(), any())).thenReturn(0);

        service.queueBackfill(1942);
        assertTrue(laneSettled());

        verify(games, never()).save(any(IgdbGame.class)); // nothing else touched
    }

    @Test
    void aBurstPartialIsPersistedForTheRangeResume() throws Exception {
        when(profiles.singleton()).thenReturn(profileWithKey());
        IgdbGame game = card(1942);
        when(games.findById(1942L)).thenReturn(Optional.of(game));
        when(matches.findSteamKeys(1942L)).thenReturn(List.of("steam:292030"));
        byte[] partial = new byte[20_000];
        when(client.resolveIcon(eq("k1"), eq("292030"), isNull(), isNull(), any()))
                .thenReturn(SgdbClient.Resolution.partial("https://cdn/icon.png", partial));

        service.queueBackfill(1942);
        assertTrue(laneSettled());

        // the partial lands as Range-resume material, keyed by its URL
        verify(games).storePartialIfUndelivered(1942L, "https://cdn/icon.png", partial);
        verify(games, never()).storeIconIfAbsent(anyLong(), anyString(), anyString(), any());

        // and the NEXT attempt (the URL is now known) resumes DIRECTLY — the
        // API leg is skipped entirely (its dead windows must not gate a retry)
        game.setSgdbIconUrl("https://cdn/icon.png");
        game.setSgdbIconPartial(partial);
        when(client.resumeKnownTarget("k1", "https://cdn/icon.png", partial))
                .thenReturn(SgdbClient.Resolution.done(
                        new SgdbClient.SgdbIcon("https://cdn/icon.png", "image/png", new byte[]{9})));
        when(games.storeIconIfAbsent(1942L, "https://cdn/icon.png", "image/png", new byte[]{9})).thenReturn(1);
        service.queueBackfill(1942);
        assertTrue(laneSettled());
        verify(client).resumeKnownTarget("k1", "https://cdn/icon.png", partial);
        verify(client, never()).resolveIcon(eq("k1"), eq("292030"), eq("https://cdn/icon.png"), eq(partial), any());
        verify(games).storeIconIfAbsent(1942L, "https://cdn/icon.png", "image/png", new byte[]{9});
    }

    @Test
    void anOverCapKnownTargetFallsBackToAFreshApiPick() throws Exception {
        when(profiles.singleton()).thenReturn(profileWithKey());
        IgdbGame game = card(1942);
        game.setSgdbIconUrl("https://cdn/fat.png"); // known, but the cap refuses it
        when(games.findById(1942L)).thenReturn(Optional.of(game));
        when(matches.findSteamKeys(1942L)).thenReturn(List.of("steam:292030"));
        when(client.resumeKnownTarget(eq("k1"), eq("https://cdn/fat.png"), any()))
                .thenReturn(null); // the direct chain's over-cap verdict
        when(client.resolveIcon(eq("k1"), eq("292030"), eq("https://cdn/fat.png"), any(), any()))
                .thenReturn(SgdbClient.Resolution.done(
                        new SgdbClient.SgdbIcon("https://cdn/thin.png", "image/png", new byte[]{5})));
        when(games.storeIconIfAbsent(anyLong(), anyString(), anyString(), any())).thenReturn(1);

        service.queueBackfill(1942);
        assertTrue(laneSettled());

        verify(client).resolveIcon(eq("k1"), eq("292030"), eq("https://cdn/fat.png"), any(), any());
        verify(games).storeIconIfAbsent(1942L, "https://cdn/thin.png", "image/png", new byte[]{5});
    }

    @Test
    void anHonestMissIsMarkedAndNeverReAsked() throws Exception {
        when(profiles.singleton()).thenReturn(profileWithKey());
        IgdbGame game = card(1942);
        when(games.findById(1942L)).thenReturn(Optional.of(game));
        when(matches.findSteamKeys(1942L)).thenReturn(List.of("steam:292030"));
        when(client.resolveIcon(eq("k1"), eq("292030"), any(), any(), any()))
                .thenReturn(SgdbClient.Resolution.miss());

        service.queueBackfill(1942);
        assertTrue(laneSettled());
        assertEquals(Boolean.TRUE, game.getSgdbIconMissing());
        verify(games).save(game);

        // The sweep and any later dogleg skip the answered miss entirely.
        when(games.findBySgdbIconBytesIsNull()).thenReturn(List.of(game));
        service.sweepUndeliveredIcons();
        assertTrue(laneSettled());
        verify(client, org.mockito.Mockito.times(1)).resolveIcon(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void theSweepRequeuesUndeliveredIcons() throws Exception {
        when(profiles.singleton()).thenReturn(profileWithKey());
        IgdbGame owed = card(1942);            // transport-starved, not answered
        IgdbGame answered = card(1943);        // the V25 marker says: no icon exists
        answered.setSgdbIconMissing(true);
        IgdbGame noSteamKey = card(1944);      // resolved by name — nothing to ask SGDB
        when(games.findBySgdbIconBytesIsNull()).thenReturn(List.of(owed, answered, noSteamKey));
        when(games.findById(1942L)).thenReturn(Optional.of(owed));
        when(matches.findSteamKeys(1942L)).thenReturn(List.of("steam:292030"));
        when(client.resolveIcon(eq("k1"), eq("292030"), any(), any(), any()))
                .thenReturn(SgdbClient.Resolution.done(
                        new SgdbClient.SgdbIcon("https://cdn/icon.png", "image/png", new byte[]{1})));
        when(games.storeIconIfAbsent(anyLong(), anyString(), anyString(), any())).thenReturn(1);

        service.sweepUndeliveredIcons();
        assertTrue(laneSettled());

        verify(client, org.mockito.Mockito.times(1)).resolveIcon(anyString(), anyString(), any(), any(), any());
        verify(games).storeIconIfAbsent(1942L, "https://cdn/icon.png", "image/png", new byte[]{1});
    }

    @Test
    void theSweepIsSkippedEntirelyWhileTheOutboundIsParked() {
        when(client.parked()).thenReturn(true);

        service.sweepUndeliveredIcons();

        verify(games, never()).findBySgdbIconBytesIsNull(); // one log line, zero work
        verify(client, never()).resolveIcon(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void aCardThatAlreadyHasAnIconIsNeverReAsked() throws Exception {
        when(profiles.singleton()).thenReturn(profileWithKey());
        IgdbGame resolved = card(1942);
        resolved.setSgdbIconBytes(new byte[]{7}); // the resolved marker is the BYTES
        when(games.findById(1942L)).thenReturn(Optional.of(resolved));

        service.queueBackfill(1942);
        assertTrue(laneSettled());
        verify(client, never()).resolveIcon(anyString(), anyString(), any(), any(), any());
        verify(games, never()).save(any(IgdbGame.class));
    }

    @Test
    void anUpstreamFailureLeavesTheCardUntouched() throws Exception {
        when(profiles.singleton()).thenReturn(profileWithKey());
        IgdbGame game = card(1942);
        when(games.findById(1942L)).thenReturn(Optional.of(game));
        when(matches.findSteamKeys(1942L)).thenReturn(List.of("steam:292030"));
        when(client.resolveIcon(anyString(), anyString(), any(), any(), any())).thenThrow(new IgdbException("parked"));

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
        verify(client, never()).resolveIcon(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void withoutAKeyNothingIsEverQueued() throws Exception {
        when(profiles.singleton()).thenReturn(new ServerProfile("OAK")); // no key
        service.queueForAppId(1942, "292030");
        assertTrue(laneSettled());
        verify(games, never()).findById(anyLong());
    }

    // --- the client-offer acceptance ---

    @Test
    void aClientOfferWinsAtomicallyAndKeepsThePendingUrlAsProvenance() {
        IgdbGame game = card(1942);
        game.setSgdbIconUrl("https://cdn/icon.png"); // the pending target the client fetched
        when(games.findById(1942L)).thenReturn(Optional.of(game));
        when(games.storeIconIfAbsent(1942L, "https://cdn/icon.png", "image/png", PNG_BYTES)).thenReturn(1);

        assertTrue(service.acceptClientIcon(1942, PNG_BYTES));
        verify(games).storeIconIfAbsent(1942L, "https://cdn/icon.png", "image/png", PNG_BYTES);
    }

    @Test
    void aClientOfferLosesToAnExistingIconWithoutSideEffects() {
        IgdbGame resolved = card(1942);
        resolved.setSgdbIconBytes(new byte[]{7});
        when(games.findById(1942L)).thenReturn(Optional.of(resolved));

        assertFalse(service.acceptClientIcon(1942, PNG_BYTES));
        verify(games, never()).storeIconIfAbsent(anyLong(), anyString(), anyString(), any());
    }

    @Test
    void nonIconBytesAreRefusedBeforeAnyStorage() {
        assertFalse(service.acceptClientIcon(1942, "not an icon at all..".getBytes()));
        verify(games, never()).findById(anyLong());
    }

    // --- the stuck-lane watch ---

    @Test
    void theStuckLaneWatchStaysQuietWithinBudgetAndNamesTheGamePastIt() {
        long startedAtMillis = 1_000_000L;
        // one second of business — nothing to say
        assertNull(SgdbIconService.stuckLaneLine(startedAtMillis, startedAtMillis + 1_000L, 1942));
        // the budget minus a hair — the last quiet tick
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
