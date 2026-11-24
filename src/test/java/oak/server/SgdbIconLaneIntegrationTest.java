package oak.server;

import com.jayway.jsonpath.JsonPath;
import oak.server.entity.IgdbGame;
import oak.server.repository.IgdbGameRepository;
import oak.server.repository.UserRepository;
import oak.server.service.sgdb.SgdbClient;
import oak.server.service.sgdb.SgdbIconService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * THE background-lane seam (2026-11-24, the missing-transaction post-mortem):
 * the icon store must work from the lane's executor thread, which has NO
 * caller transaction — a plain {@code @Modifying} query threw
 * {@code TransactionRequiredException} there for a whole release while every
 * {@code @Transactional} test stayed green (the test's own transaction gifted
 * the store exactly what production lacked).
 *
 * <p>Deliberately NOT {@code @Transactional}: the lane thread must SEE the
 * fixtures, so they are committed for real — which is also why this class
 * lives alone and cleans up after itself (@AfterEach wipes the users it
 * registered, so the shared-context bootstrap tests never see a non-empty
 * users table from here). The SGDB door is a mock (no network); the service,
 * the lane and the repository are the REAL beans.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SgdbIconLaneIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestInvites testInvites;

    @Autowired
    private IgdbGameRepository igdbGamesCache;

    @Autowired
    private UserRepository users;

    @Autowired
    private oak.server.repository.RefreshTokenRepository refreshTokens;

    @Autowired
    private SgdbIconService sgdbIcons; // the REAL service — its lane is the seam under test

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private SgdbClient sgdbClient; // no network: the resolution is stubbed

    private String ownerToken;

    @BeforeEach
    void registerOwner() throws Exception {
        ownerToken = registerAndLogin("lane-owner", "supersecret"); // first = OWNER
    }

    @AfterEach
    void wipeCommittedFixtures() {
        refreshTokens.deleteAll(); // the login's rows FK-reference the users
        users.deleteAll(); // the owner registered OUTSIDE any test transaction
        igdbGamesCache.deleteById(555_020L);
    }

    @Test
    void theBackgroundLaneStoresIconsThroughARealTransaction() throws Exception {
        byte[] iconBytes = new byte[]{
                (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 4, 4, 4};
        // committed fixtures: the key (via the real endpoint) and the target card
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/server/sgdb").header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON).content("{\"apiKey\":\"k1\"}"))
                .andExpect(status().isOk());
        igdbGamesCache.save(new IgdbGame(555_020L, "Lane Target", null, "[]", null, java.time.Instant.now()));
        when(sgdbClient.parked()).thenReturn(false);
        when(sgdbClient.resolveIcon(ArgumentMatchers.eq("k1"), ArgumentMatchers.eq("292030"),
                ArgumentMatchers.isNull(), ArgumentMatchers.isNull(), ArgumentMatchers.any()))
                .thenReturn(SgdbClient.Resolution.done(new SgdbClient.SgdbIcon(
                        "https://cdn2.steamgriddb.com/icon/lane.png", "image/png", iconBytes)));

        sgdbIcons.queueForAppId(555_020L, "292030"); // the REAL queue → REAL lane

        IgdbGame stored = null;
        long deadlineMillis = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadlineMillis) {
            IgdbGame row = igdbGamesCache.findById(555_020L).orElse(null);
            if (row != null && row.getSgdbIconBytes() != null) {
                stored = row;
                break;
            }
            Thread.sleep(50);
        }
        assertNotNull(stored,
                "the background lane did not store the icon within 5 s — the store path is broken off-thread");
        assertArrayEquals(iconBytes, stored.getSgdbIconBytes());
        assertEquals("https://cdn2.steamgriddb.com/icon/lane.png", stored.getSgdbIconUrl());
    }

    private String registerAndLogin(String username, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(testInvites.registrationBody(username, password)))
                .andExpect(status().isCreated());
        MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(JSON)
                        .content(String.format("{\"username\":\"%s\",\"password\":\"%s\"}", username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(login.getResponse().getContentAsString(), "$.token");
    }
}
