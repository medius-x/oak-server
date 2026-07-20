package oak.server;

import com.jayway.jsonpath.JsonPath;
import oak.server.entity.RefreshToken;
import oak.server.entity.User;
import oak.server.repository.RefreshTokenRepository;
import oak.server.repository.UserRepository;
import oak.server.service.RefreshTokenCleanup;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end auth flow through MockMvc against the full Spring context backed by
 * an in-memory H2 database (test profile). Acts as a safety net over the auth
 * behavior: HTTP statuses, bean validation, JWT issuance/validation, BCrypt.
 *
 * <p>{@code @Transactional} rolls back each test method, so methods are isolated.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuthFlowIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenCleanup refreshTokenCleanup;

    @Test
    void register_returnsCreatedUserWithoutPasswordHash() throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(regBody("alice", "supersecret")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void register_duplicateUsername_returns409() throws Exception {
        register("alice", "supersecret");

        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(regBody("alice", "supersecret")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void register_invalidBody_returns400() throws Exception {
        // username too short (@Size min 3) and password too short (@Size min 8) -> bean validation 400
        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(regBody("ab", "123")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void login_validCredentials_returnsBearerToken() throws Exception {
        register("alice", "supersecret");

        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType(JSON)
                        .content(loginBody("alice", "supersecret")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").isNumber())
                .andReturn();

        String token = JsonPath.read(result.getResponse().getContentAsString(), "$.token");
        assertEquals(3, token.split("\\.").length, "a JWT has three dot-separated parts");
    }

    @Test
    void login_wrongPassword_returns401() throws Exception {
        register("alice", "supersecret");

        mockMvc.perform(post("/api/auth/login").contentType(JSON)
                        .content(loginBody("alice", "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void me_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void me_withValidToken_returnsProfile() throws Exception {
        register("alice", "supersecret");
        String token = login("alice", "supersecret");

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void me_withTamperedToken_returns401() throws Exception {
        register("alice", "supersecret");
        String token = login("alice", "supersecret");

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token + "XYZ"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refresh_rotatesToken_issuesNewPair_andRejectsReuse() throws Exception {
        register("alice", "supersecret");
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login").contentType(JSON)
                        .content(loginBody("alice", "supersecret")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").isString())
                .andReturn();
        String loginBody = loginResult.getResponse().getContentAsString();
        String accessToken = JsonPath.read(loginBody, "$.token");
        String refreshToken = JsonPath.read(loginBody, "$.refreshToken");

        // refresh succeeds and issues a NEW access + NEW refresh
        MvcResult refreshResult = mockMvc.perform(post("/api/auth/refresh").contentType(JSON)
                        .content(refreshBody(refreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.refreshToken").isString())
                .andReturn();
        String refreshedBody = refreshResult.getResponse().getContentAsString();
        String newAccess = JsonPath.read(refreshedBody, "$.token");
        String newRefresh = JsonPath.read(refreshedBody, "$.refreshToken");

        assertNotEquals(refreshToken, newRefresh, "refresh rotates the refresh token");

        // the new access token is valid against a protected endpoint
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + newAccess))
                .andExpect(status().isOk());

        // the OLD refresh token is now revoked (rotation) -> 401 on reuse
        mockMvc.perform(post("/api/auth/refresh").contentType(JSON).content(refreshBody(refreshToken)))
                .andExpect(status().isUnauthorized());

        // a bogus refresh token -> 401
        mockMvc.perform(post("/api/auth/refresh").contentType(JSON).content(refreshBody("not-a-real-token")))
                .andExpect(status().isUnauthorized());

        // sanity: the original access token is unaffected by refresh (still structurally valid JWT)
        assertEquals(3, accessToken.split("\\.").length);
    }

    @Test
    void refresh_reuse_revokesEntireFamily() throws Exception {
        register("alice", "supersecret");
        String firstRefresh = loginRefresh("alice", "supersecret");  // T1
        String secondRefresh = refreshAndGet(firstRefresh);          // T2 (T1 revoked)
        String thirdRefresh = refreshAndGet(secondRefresh);          // T3 (T2 revoked; the live one)

        // Reusing the already-rotated T1 is a theft signal -> 401 ...
        mockMvc.perform(post("/api/auth/refresh").contentType(JSON).content(refreshBody(firstRefresh)))
                .andExpect(status().isUnauthorized());

        // ... and it kills the WHOLE family, so the still-live T3 no longer works either.
        mockMvc.perform(post("/api/auth/refresh").contentType(JSON).content(refreshBody(thirdRefresh)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refresh_expired_doesNotRevokeFamily() throws Exception {
        register("alice", "supersecret");
        User alice = userRepository.findByUsername("alice").orElseThrow();
        String family = "family-expiry-test";
        // A live sibling and an expired sibling in the SAME family.
        persistToken(alice, "live-secret", family, Instant.now().plus(Duration.ofHours(1)));
        persistToken(alice, "expired-secret", family, Instant.now().minus(Duration.ofHours(1)));

        // Refreshing the expired one -> 401, but expiry is NOT theft...
        mockMvc.perform(post("/api/auth/refresh").contentType(JSON).content(refreshBody("expired-secret")))
                .andExpect(status().isUnauthorized());

        // ... so the live sibling in the same family still refreshes fine.
        mockMvc.perform(post("/api/auth/refresh").contentType(JSON).content(refreshBody("live-secret")))
                .andExpect(status().isOk());
    }

    @Test
    void cleanup_purgesExpiredTokens_keepsLive() throws Exception {
        register("alice", "supersecret");
        User alice = userRepository.findByUsername("alice").orElseThrow();
        String family = "family-cleanup";
        persistToken(alice, "expired", family, Instant.now().minus(Duration.ofHours(1)));
        persistToken(alice, "live", family, Instant.now().plus(Duration.ofHours(1)));

        long before = refreshTokenRepository.count();
        long deleted = refreshTokenCleanup.purgeExpired();
        long after = refreshTokenRepository.count();

        assertEquals(2L, before, "two tokens seeded");
        assertEquals(1L, deleted, "only the expired token is purged");
        assertEquals(1L, after, "the live token remains");
        assertTrue(refreshTokenRepository.findByTokenHash(sha256hex("live")).isPresent(),
                "the live token survives the purge");
    }

    // --- helpers ---

    private void register(String username, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(regBody(username, password)))
                .andExpect(status().isCreated());
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType(JSON)
                        .content(loginBody(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    /** Login and return the refresh token (not the access JWT). */
    private String loginRefresh(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType(JSON)
                        .content(loginBody(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.refreshToken");
    }

    /** Refresh successfully and return the newly issued refresh token. */
    private String refreshAndGet(String refreshToken) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/refresh").contentType(JSON)
                        .content(refreshBody(refreshToken)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.refreshToken");
    }

    /** Inserts a refresh token row with a known raw secret, family and expiry (hashed like the server does). */
    private void persistToken(User user, String rawSecret, String family, Instant expiresAt) {
        refreshTokenRepository.save(new RefreshToken(user, sha256hex(rawSecret), expiresAt, family));
    }

    /** SHA-256 hex of the raw token — mirrors AuthService.hashToken so lookups match. */
    private static String sha256hex(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 not available", unavailable);
        }
    }

    private static String regBody(String username, String password) {
        return String.format("{\"username\":\"%s\",\"password\":\"%s\"}", username, password);
    }

    private static String loginBody(String username, String password) {
        return String.format("{\"username\":\"%s\",\"password\":\"%s\"}", username, password);
    }

    private static String refreshBody(String refreshToken) {
        return String.format("{\"refreshToken\":\"%s\"}", refreshToken);
    }
}
