package oak.server;

import com.jayway.jsonpath.JsonPath;
import oak.server.dto.GameActivityChangedEvent;
import oak.server.dto.PresenceActivity;
import oak.server.entity.IgdbGame;
import oak.server.repository.IgdbGameRepository;
import oak.server.service.igdb.IgdbClient;
import oak.server.service.igdb.IgdbLookupRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc coverage of the IGDB round-1 endpoints on the in-memory H2 profile with the
 * outbound {@link IgdbClient} mocked: the 503/403/400/429 gates, the full match
 * ladder (Steam appid → exact-name search → candidates), the cache discipline (a hit
 * and a fresh miss never touch IGDB twice; an ambiguous result caches nothing), the
 * confirm pinning, and the Apicalypse-injection neutralization on the search body.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class IgdbGamesIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestInvites testInvites;

    /** Publishes tracker events directly (the session listener is synchronous). */
    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private IgdbGameRepository igdbGamesCache;

    @MockitoBean
    private IgdbClient igdbClient;

    /** The cover proxy is mocked: its fetch-on-miss leg would touch the real CDN. */
    @MockitoBean
    private oak.server.service.igdb.IgdbCoverService coverService;

    private String ownerToken;

    /** Fresh ordinary member per TEST: the lookup rate limiter is a context-wide
     *  singleton (in-memory, not transactional), so a spray test must not poison the
     *  window of every later test's user. */
    private static final java.util.concurrent.atomic.AtomicInteger MEMBER_SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

    @BeforeEach
    void registerOwner() throws Exception {
        ownerToken = registerAndLogin("owner", "supersecret");   // first = OWNER
    }

    private String memberToken() throws Exception {
        return registerAndLogin("member" + MEMBER_SEQ.incrementAndGet(), "supersecret");
    }

    // --- configuration gates ---

    @Test
    void lookupAnswers503AndHidesTheCapabilityWhileUnconfigured() throws Exception {
        String memberToken = memberToken();
        mockMvc.perform(get("/api/games/lookup").header("Authorization", "Bearer " + memberToken)
                        .param("exeName", "eldenring.exe"))
                .andExpect(status().isServiceUnavailable());

        String capabilities = mockMvc
                .perform(get("/api/server").header("Authorization", "Bearer " + memberToken))
                .andExpect(jsonPath("$.capabilities").isArray())
                .andReturn().getResponse().getContentAsString();
        assertFalse(capabilities.contains("igdb-games"));
    }

    @Test
    void settingsAreWriteOnlyForManagersAndNeverEchoTheSecret() throws Exception {
        String memberToken = memberToken();
        // an ordinary member can neither read nor write the integration settings
        mockMvc.perform(get("/api/server/igdb").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/server/igdb").header("Authorization", "Bearer " + memberToken)
                        .contentType(JSON).content("{\"clientId\":\"x\",\"clientSecret\":\"y\"}"))
                .andExpect(status().isForbidden());

        // the owner configures the pair; the answer carries the id + configured only
        String settings = mockMvc
                .perform(patch("/api/server/igdb").header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON)
                        .content("{\"clientId\":\"cid123\",\"clientSecret\":\"supersecret456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientId").value("cid123"))
                .andExpect(jsonPath("$.configured").value(true))
                .andReturn().getResponse().getContentAsString();
        assertFalse(settings.contains("supersecret456"));

        // the capability lights up for EVERY member (the profile was re-pushed)
        String capabilities = mockMvc
                .perform(get("/api/server").header("Authorization", "Bearer " + memberToken))
                .andReturn().getResponse().getContentAsString();
        assertTrue(capabilities.contains("igdb-games"));
    }

    @Test
    void sgdbSettingsAreWriteOnlyAndFlipTheIconCapability() throws Exception {
        String memberToken = memberToken();
        // an ordinary member can neither read nor write the icon key
        mockMvc.perform(get("/api/server/sgdb").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/server/sgdb").header("Authorization", "Bearer " + memberToken)
                        .contentType(JSON).content("{\"apiKey\":\"nope\"}"))
                .andExpect(status().isForbidden());

        // the owner stores the key; the answer carries the configured flag ONLY
        String settings = mockMvc
                .perform(patch("/api/server/sgdb").header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON).content("{\"apiKey\":\"sgdb-key-123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andReturn().getResponse().getContentAsString();
        assertFalse(settings.contains("sgdb-key-123"));
        // the key never rides the server profile either
        assertFalse(mockMvc.perform(get("/api/server").header("Authorization", "Bearer " + ownerToken))
                .andReturn().getResponse().getContentAsString().contains("sgdb-key-123"));

        // the icon capability lights up while the key is stored…
        String litUp = mockMvc
                .perform(get("/api/server").header("Authorization", "Bearer " + memberToken))
                .andReturn().getResponse().getContentAsString();
        assertTrue(litUp.contains("sgdb-icons"));

        // …and an EMPTY string clears it (the Disconnect button) — off again
        mockMvc.perform(patch("/api/server/sgdb").header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON).content("{\"apiKey\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false));
        String offAgain = mockMvc
                .perform(get("/api/server").header("Authorization", "Bearer " + memberToken))
                .andReturn().getResponse().getContentAsString();
        assertFalse(offAgain.contains("sgdb-icons"));
    }

    // --- the ladder -----

    @Test
    void theIconEndpointServesTheCachedBytesAnd404sWithoutThem() throws Exception {
        String memberToken = memberToken();
        // The serving gate: the icon layer must be ON (key stored, not paused).
        mockMvc.perform(patch("/api/server/sgdb").header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON).content("{\"apiKey\":\"k1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true));
        IgdbGame withIcon = new IgdbGame(555_001L, "With Icon", null, "[]", null,
                java.time.Instant.now());
        withIcon.setSgdbIconUrl("https://cdn2.steamgriddb.com/icon/x.png");
        withIcon.setSgdbIconMime("image/png");
        withIcon.setSgdbIconBytes(new byte[]{1, 2, 3});
        igdbGamesCache.save(withIcon);
        IgdbGame withoutIcon = igdbGamesCache.save(new IgdbGame(555_002L, "Without Icon", null, "[]",
                null, java.time.Instant.now()));

        mockMvc.perform(get("/api/games/555001/icon").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(new byte[]{1, 2, 3}));
        mockMvc.perform(get("/api/games/555002/icon").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/games/555001/icon"))
                .andExpect(status().isUnauthorized()); // no anonymous icon pulls

        // PAUSE: the cached value deliberately stops being SERVED (user semantics)
        mockMvc.perform(patch("/api/server/sgdb").header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON).content("{\"paused\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true)) // the key is kept
                .andExpect(jsonPath("$.paused").value(true));
        mockMvc.perform(get("/api/games/555001/icon").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isNotFound());
        // …and back on:
        mockMvc.perform(patch("/api/server/sgdb").header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON).content("{\"paused\":false}"))
                .andExpect(jsonPath("$.paused").value(false));
        mockMvc.perform(get("/api/games/555001/icon").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk());
    }

    @Test
    void theCoverEndpointProxiesTheThumbnailAndGatesOnTheGameIntegration() throws Exception {
        String memberToken = memberToken();
        igdbGamesCache.save(new IgdbGame(555_003L, "Covered", "co555003", "[]", null,
                java.time.Instant.now()));
        // Without IGDB credentials the whole game stack is off — 503, no proxying.
        mockMvc.perform(get("/api/games/555003/cover").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isServiceUnavailable());

        configure(); // the owner sets the Twitch pair → the stack is live
        org.mockito.Mockito.when(coverService.coverThumb(555_003L)).thenReturn(new byte[]{7, 7});
        mockMvc.perform(get("/api/games/555003/cover").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(content().bytes(new byte[]{7, 7}));
        org.mockito.Mockito.when(coverService.coverThumb(555_004L)).thenReturn(null);
        mockMvc.perform(get("/api/games/555004/cover").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void steamAppidHitResolvesOnceThenServesFromCache() throws Exception {
        String memberToken = memberToken();
        configure();
        when(igdbClient.query(anyString(), contains("external_games.external_game_source = 1")))
                .thenReturn(igdb("[{\"id\":1942,\"name\":\"The Witcher 3: Wild Hunt\","
                        + "\"cover\":{\"image_id\":\"co1tiw\"},"
                        + "\"genres\":[{\"id\":31,\"name\":\"Adventure\"}],"
                        + "\"first_release_date\":1431993600}]"));

        mockMvc.perform(get("/api/games/lookup").header("Authorization", "Bearer " + memberToken)
                        .param("appId", "292030").param("exeName", "witcher3.exe")
                        .param("folderName", "The Witcher 3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.game.igdbId").value(1942))
                .andExpect(jsonPath("$.game.name").value("The Witcher 3: Wild Hunt"))
                .andExpect(jsonPath("$.game.coverImageId").value("co1tiw"))
                .andExpect(jsonPath("$.game.genres[0].name").value("Adventure"))
                .andExpect(jsonPath("$.game.firstReleaseDate").value(1431993600))
                .andExpect(jsonPath("$.candidates").isEmpty());

        // the second lookup never leaves the cache
        mockMvc.perform(get("/api/games/lookup").header("Authorization", "Bearer " + memberToken)
                        .param("appId", "292030"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.game.igdbId").value(1942));
        verify(igdbClient, times(1)).query(anyString(), anyString());
    }

    @Test
    void steamMissRecordsACacheEntryAndSkipsIgdbOnRepeat() throws Exception {
        String memberToken = memberToken();
        configure();
        when(igdbClient.query(anyString(), anyString())).thenReturn(igdb("[]"));

        mockMvc.perform(get("/api/games/lookup").header("Authorization", "Bearer " + memberToken)
                        .param("appId", "730"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.game").doesNotExist())
                .andExpect(jsonPath("$.candidates").isEmpty());

        mockMvc.perform(get("/api/games/lookup").header("Authorization", "Bearer " + memberToken)
                        .param("appId", "730"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.game").doesNotExist());
        verify(igdbClient, times(1)).query(anyString(), anyString()); // the miss was cached
    }

    @Test
    void exactNameMatchResolvesConfidently() throws Exception {
        String memberToken = memberToken();
        configure();
        when(igdbClient.query(anyString(), contains("search")))
                .thenReturn(igdb("[{\"id\":1030,\"name\":\"ELDEN RING\",\"genres\":[]}]"));

        mockMvc.perform(get("/api/games/lookup").header("Authorization", "Bearer " + memberToken)
                        .param("folderName", "ELDEN RING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.game.igdbId").value(1030))
                .andExpect(jsonPath("$.candidates").isEmpty());
    }

    @Test
    void ambiguousSearchReturnsCandidatesAndDeliberatelyCachesNothing() throws Exception {
        String memberToken = memberToken();
        configure();
        when(igdbClient.query(anyString(), contains("search")))
                .thenReturn(igdb("[{\"id\":1,\"name\":\"Elden Ring Nightreign\",\"genres\":[]},"
                        + "{\"id\":2,\"name\":\"Elden Gunfire\",\"genres\":[]}]"));

        mockMvc.perform(get("/api/games/lookup").header("Authorization", "Bearer " + memberToken)
                        .param("exeName", "eldenring.exe"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.game").doesNotExist())
                .andExpect(jsonPath("$.candidates.length()").value(2));

        // nothing pinned: a repeated lookup re-searches honestly
        mockMvc.perform(get("/api/games/lookup").header("Authorization", "Bearer " + memberToken)
                        .param("exeName", "eldenring.exe"))
                .andExpect(jsonPath("$.candidates.length()").value(2));
        verify(igdbClient, times(2)).query(anyString(), contains("search"));
    }

    @Test
    void confirmPinsThePickAndTheNextLookupSkipsIgdbEntirely() throws Exception {
        String memberToken = memberToken();
        configure();
        when(igdbClient.query(anyString(), contains("search")))
                .thenReturn(igdb("[{\"id\":1,\"name\":\"Elden Ring Nightreign\",\"genres\":[]},"
                        + "{\"id\":2,\"name\":\"ELDEN RING\",\"genres\":[]}]"));
        mockMvc.perform(get("/api/games/lookup").header("Authorization", "Bearer " + memberToken)
                        .param("exeName", "eldenring.exe"))
                .andExpect(jsonPath("$.candidates.length()").value(2));

        mockMvc.perform(post("/api/games/confirm").header("Authorization", "Bearer " + memberToken)
                        .contentType(JSON)
                        .content("{\"exeName\":\"eldenring.exe\",\"igdbId\":2}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/games/lookup").header("Authorization", "Bearer " + memberToken)
                        .param("exeName", "eldenring.exe"))
                .andExpect(jsonPath("$.game.igdbId").value(2))
                .andExpect(jsonPath("$.candidates").isEmpty());
        verify(igdbClient, times(1)).query(anyString(), anyString()); // only the first search
    }

    @Test
    void playSessionsAccumulateFromTrackerEventsAndMyGamesServesThem() throws Exception {
        configure();
        when(igdbClient.query(anyString(), contains("external_games.external_game_source = 1")))
                .thenReturn(igdb("[{\"id\":1942,\"name\":\"The Witcher 3: Wild Hunt\","
                        + "\"cover\":{\"image_id\":\"co1tiw\"},\"genres\":[],\"first_release_date\":1431993600}]"));
        String player = "player" + MEMBER_SEQ.incrementAndGet();
        String playerToken = registerAndLogin(player, "supersecret");
        mockMvc.perform(get("/api/games/lookup").header("Authorization", "Bearer " + playerToken)
                        .param("appId", "292030"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.game.igdbId").value(1942));

        // the game line appears, then clears — exactly the tracker's derived events
        events.publishEvent(new GameActivityChangedEvent(player,
                new PresenceActivity("playing", "The Witcher 3: Wild Hunt", 1942L)));
        events.publishEvent(new GameActivityChangedEvent(player, null));

        mockMvc.perform(get("/api/games/my").header("Authorization", "Bearer " + playerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].igdbId").value(1942))
                .andExpect(jsonPath("$[0].name").value("The Witcher 3: Wild Hunt"))
                .andExpect(jsonPath("$[0].coverImageId").value("co1tiw"))
                .andExpect(jsonPath("$[0].genres").isArray())
                .andExpect(jsonPath("$[0].totalSeconds").isNumber())
                .andExpect(jsonPath("$[0].lastPlayedAt").isNotEmpty());
    }

    @Test
    void myGamesRequiresAuthenticationAndIsEmptyForANewcomer() throws Exception {
        mockMvc.perform(get("/api/games/my"))
                .andExpect(status().isUnauthorized());
        String memberToken = memberToken();
        configure();
        mockMvc.perform(get("/api/games/my").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void aStaleCachedCardRefreshesLazilyAfterACacheHit() throws Exception {
        configure();
        when(igdbClient.query(anyString(), contains("external_games.external_game_source = 1")))
                .thenReturn(igdb("[{\"id\":1942,\"name\":\"The Witcher 3: Wild Hunt\","
                        + "\"cover\":{\"image_id\":\"co1tiw\"},\"genres\":[],\"first_release_date\":1431993600}]"));
        String memberToken = memberToken();
        mockMvc.perform(get("/api/games/lookup").header("Authorization", "Bearer " + memberToken)
                        .param("appId", "292030"))
                .andExpect(status().isOk());
        verify(igdbClient, times(1)).query(anyString(), anyString());

        // age the cached card past the day TTL; the next lookup still answers
        // from the CACHE while the refresh runs on the background lane
        IgdbGame cached = igdbGamesCache.findById(1942L).orElseThrow();
        cached.setCachedAt(java.time.Instant.now().minus(java.time.Duration.ofDays(2)));
        igdbGamesCache.save(cached);
        when(igdbClient.query(anyString(), contains("where id = 1942")))
                .thenReturn(igdb("[{\"id\":1942,\"name\":\"The Witcher 3: Wild Hunt (Updated)\","
                        + "\"cover\":{\"image_id\":\"coNEW\"},\"genres\":[],\"first_release_date\":1431993600}]"));
        mockMvc.perform(get("/api/games/lookup").header("Authorization", "Bearer " + memberToken)
                        .param("appId", "292030"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.game.coverImageId").value("co1tiw")); // cache answer, instant
        verify(igdbClient, timeout(5_000)).query(anyString(), contains("where id = 1942"));
    }

    @Test
    void pausingTheIntegrationKeepsTheCredentialsAndAnswers503() throws Exception {
        String memberToken = memberToken();
        configure(); // credentials stored, the pause switch untouched (null = not paused)

        // pause: the ladder goes 503 and the capability disappears…
        mockMvc.perform(patch("/api/server/igdb").header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON).content("{\"paused\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientId").value("cid123"))   // credentials KEPT
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.paused").value(true));
        mockMvc.perform(get("/api/games/lookup").header("Authorization", "Bearer " + memberToken)
                        .param("appId", "292030"))
                .andExpect(status().isServiceUnavailable());
        String capabilities = mockMvc
                .perform(get("/api/server").header("Authorization", "Bearer " + memberToken))
                .andReturn().getResponse().getContentAsString();
        assertFalse(capabilities.contains("igdb-games"));
        verify(igdbClient, times(0)).query(anyString(), anyString()); // never left the gate

        // …and a one-click restore brings everything back (credentials never moved)
        mockMvc.perform(patch("/api/server/igdb").header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON).content("{\"paused\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.paused").value(false));
        String restored = mockMvc
                .perform(get("/api/server").header("Authorization", "Bearer " + memberToken))
                .andReturn().getResponse().getContentAsString();
        assertTrue(restored.contains("igdb-games"));
    }

    @Test
    void disconnectKeepsThePausePositionSoFreshKeysRestoreTheExactState() throws Exception {
        configure();
        // park first, then disconnect: the switch position must survive
        mockMvc.perform(patch("/api/server/igdb").header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON).content("{\"paused\":true}"))
                .andExpect(jsonPath("$.paused").value(true));
        mockMvc.perform(patch("/api/server/igdb").header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON).content("{\"clientId\":\"\",\"clientSecret\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.paused").value(true)); // the switch did NOT move
    }

    @Test
    void confirmOfANeverSeenGameIsRejected() throws Exception {
        String memberToken = memberToken();
        configure();
        mockMvc.perform(post("/api/games/confirm").header("Authorization", "Bearer " + memberToken)
                        .contentType(JSON)
                        .content("{\"exeName\":\"whatever.exe\",\"igdbId\":999}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cardEndpointServesCachesAnd404s() throws Exception {
        String memberToken = memberToken();
        configure();
        when(igdbClient.query(anyString(), contains("where id = 1942")))
                .thenReturn(igdb("[{\"id\":1942,\"name\":\"The Witcher 3: Wild Hunt\",\"genres\":[]}]"));
        when(igdbClient.query(anyString(), contains("where id = 424242")))
                .thenReturn(igdb("[]"));

        mockMvc.perform(get("/api/games/1942").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("The Witcher 3: Wild Hunt"));
        mockMvc.perform(get("/api/games/1942").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk());
        verify(igdbClient, times(1)).query(anyString(), anyString()); // second call: cache

        mockMvc.perform(get("/api/games/424242").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isNotFound());
    }

    // --- input gates ---

    @Test
    void malformedAndEmptyInputsAreRejectedBeforeIgdb() throws Exception {
        String memberToken = memberToken();
        configure();
        mockMvc.perform(get("/api/games/lookup").header("Authorization", "Bearer " + memberToken)
                        .param("appId", "730\"; drop")) // injection in the app id
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/games/lookup").header("Authorization", "Bearer " + memberToken)
                        .param("exeName", "a".repeat(300)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/games/lookup").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isBadRequest()); // nothing to look up
        verify(igdbClient, times(0)).query(anyString(), anyString());
    }

    @Test
    void apicalypseMetaCharactersNeverReachTheQueryBody() throws Exception {
        String memberToken = memberToken();
        configure();
        when(igdbClient.query(anyString(), anyString())).thenReturn(igdb("[]"));

        mockMvc.perform(get("/api/games/lookup").header("Authorization", "Bearer " + memberToken)
                        .param("folderName", "weird\"; limit 500; where id = 1"))
                .andExpect(status().isOk());

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(igdbClient).query(anyString(), body.capture());
        String sent = body.getValue();
        // the sanitized title rides INSIDE the search quotes; the injection attempt
        // (closing them early, starting a new statement) is gone
        assertTrue(sent.startsWith("search \"weird limit 500 where id = 1\";"));
        assertFalse(sent.contains("\"; limit"));
    }

    @Test
    void lookupSprayIsThrottledPerUser() throws Exception {
        String memberToken = memberToken();
        configure();
        when(igdbClient.query(anyString(), anyString())).thenReturn(igdb("[]"));
        mockMvc.perform(post("/api/games/confirm").header("Authorization", "Bearer " + memberToken)
                        .contentType(JSON).content("{\"exeName\":\"eldenring.exe\",\"igdbId\":1}"))
                .andExpect(status().isBadRequest()); // unknown game — but it BURNS one slot
        for (int attempt = 0; attempt < IgdbLookupRateLimiter.CAP - 1; attempt++) {
            mockMvc.perform(get("/api/games/lookup").header("Authorization", "Bearer " + memberToken)
                            .param("appId", "730"))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/games/lookup").header("Authorization", "Bearer " + memberToken)
                        .param("appId", "730"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void everythingRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/games/lookup").param("appId", "730"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/games/1942"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/games/confirm").contentType(JSON)
                        .content("{\"exeName\":\"a.exe\",\"igdbId\":1}"))
                .andExpect(status().isUnauthorized());
    }

    // --- helpers ---

    private void configure() throws Exception {
        mockMvc.perform(patch("/api/server/igdb").header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON)
                        .content("{\"clientId\":\"cid123\",\"clientSecret\":\"csec456\"}"))
                .andExpect(status().isOk());
    }

    private static JsonNode igdb(String json) {
        return MAPPER.readTree(json);
    }

    private String registerAndLogin(String username, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(testInvites.registrationBody(username, password)))
                .andExpect(status().isCreated());
        MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(JSON)
                        .content(String.format("{\"username\":\"%s\",\"password\":\"%s\"}",
                                username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(login.getResponse().getContentAsString(), "$.token");
    }
}
