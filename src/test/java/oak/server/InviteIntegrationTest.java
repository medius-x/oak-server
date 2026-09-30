package oak.server;

import com.jayway.jsonpath.JsonPath;
import oak.server.entity.Invite;
import oak.server.entity.ServerProfile;
import oak.server.repository.InviteRepository;
import oak.server.repository.ServerProfileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The invite-gated registration and the Invites settings endpoints, end to end. The FIRST
 * registration of each test runs on an empty instance ({@code @Transactional} isolation)
 * and is the OWNER bootstrap; every later member arrives through a minted invite.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class InviteIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestInvites testInvites;

    @Autowired
    private InviteRepository inviteRepository;

    @Autowired
    private ServerProfileRepository profileRepository;

    @Test
    void bootstrapRegisterOnEmptyServerStillWorks() throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(regBody("first-owner", "supersecret", null, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("first-owner"))
                .andExpect(jsonPath("$.displayName").value("first-owner"));
    }

    @Test
    void registerOnNonEmptyServerWithoutInviteIsForbidden() throws Exception {
        registerBootstrapOwner();

        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(regBody("intruder", "supersecret", null, null)))
                .andExpect(status().isForbidden());
    }

    @Test
    void registerWithValidInviteCreatesMemberWithNickname() throws Exception {
        registerBootstrapOwner();
        String ownerToken = login("first-owner");
        String secret = createInvite(ownerToken);

        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(regBody("newbie", "supersecret", "Newbie-Nick", secret)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("newbie"))
                .andExpect(jsonPath("$.displayName").value("Newbie-Nick"));

        // a spent invite is GONE immediately (the claim is a DELETE, not a flag)
        assertTrue(inviteRepository.findBySecret(secret).isEmpty(), "the invite row is deleted on use");
    }

    @Test
    void inviteSecretIsSingleUse() throws Exception {
        registerBootstrapOwner();
        String secret = createInvite(login("first-owner"));

        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(regBody("first-taker", "supersecret", null, secret)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(regBody("second-taker", "supersecret", null, secret)))
                .andExpect(status().isForbidden());
    }

    @Test
    void expiredInviteIsRejected() throws Exception {
        registerBootstrapOwner();
        String secret = createInvite(login("first-owner"));
        Invite invite = inviteRepository.findBySecret(secret).orElseThrow();
        invite.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
        inviteRepository.save(invite);

        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(regBody("latecomer", "supersecret", null, secret)))
                .andExpect(status().isForbidden());
    }

    @Test
    void revokedInviteIsRejected() throws Exception {
        registerBootstrapOwner();
        String ownerToken = login("first-owner");
        String secret = createInvite(ownerToken);
        Long inviteId = inviteRepository.findBySecret(secret).orElseThrow().getId();

        mockMvc.perform(delete("/api/invites/" + inviteId).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(regBody("latecomer", "supersecret", null, secret)))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownSecretIsRejected() throws Exception {
        registerBootstrapOwner();

        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(regBody("ghost", "supersecret", null, "no-such-secret")))
                .andExpect(status().isForbidden());
    }

    @Test
    void plebeianCannotManageInvites() throws Exception {
        registerBootstrapOwner();
        String ownerToken = login("first-owner");
        Long inviteId = inviteRepository.findBySecret(createInvite(ownerToken)).orElseThrow().getId();
        String plebeianToken = registerAndLoginInvited("plebeian");

        mockMvc.perform(get("/api/invites").header("Authorization", "Bearer " + plebeianToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/invites").header("Authorization", "Bearer " + plebeianToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/invites/" + inviteId).header("Authorization", "Bearer " + plebeianToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/invites/expire-all").header("Authorization", "Bearer " + plebeianToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/server/invite-settings").header("Authorization", "Bearer " + plebeianToken)
                        .contentType(JSON).content("{\"ttlMinutes\":120}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void createdLinkHasInviteShape() throws Exception {
        registerBootstrapOwner();
        // The seeder's fresh rows carry the advertised localhost:8080, so the link is predictable.
        MvcResult result = mockMvc.perform(post("/api/invites").header("Authorization", "Bearer " + login("first-owner")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdBy").value("first-owner"))
                .andExpect(jsonPath("$.secretPreview").isString())
                .andReturn();
        String link = JsonPath.read(result.getResponse().getContentAsString(), "$.link");
        assertTrue(link.matches("^http://localhost:8080/#invite=[A-Za-z0-9_-]{32}$"),
                "unexpected link shape: " + link);
    }

    @Test
    void linkFallsBackToHostHeaderWhenNotAdvertised() throws Exception {
        registerBootstrapOwner();
        ServerProfile profile = profileRepository.singleton();
        profile.setConnectionHost(null);
        profile.setConnectionPort(null);
        profileRepository.save(profile);

        MvcResult result = mockMvc.perform(post("/api/invites")
                        .header("Authorization", "Bearer " + login("first-owner"))
                        .header(HttpHeaders.HOST, "oak.lan:9000"))
                .andExpect(status().isCreated())
                .andReturn();
        String link = JsonPath.read(result.getResponse().getContentAsString(), "$.link");
        assertTrue(link.startsWith("http://oak.lan:9000/#invite="), "unexpected link: " + link);
    }

    @Test
    void listShowsOnlyActiveInvites() throws Exception {
        registerBootstrapOwner();
        String ownerToken = login("first-owner");
        String spent = createInvite(ownerToken);
        String revoked = createInvite(ownerToken);
        String alive = createInvite(ownerToken);

        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(regBody("taker", "supersecret", null, spent)))
                .andExpect(status().isCreated());
        mockMvc.perform(delete("/api/invites/" + inviteRepository.findBySecret(revoked).orElseThrow().getId())
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/invites").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ttlMinutes").value(1440))
                .andExpect(jsonPath("$.invites.length()").value(1))
                .andExpect(jsonPath("$.invites[0].secretPreview")
                        .value(inviteRepository.findBySecret(alive).orElseThrow().getSecret().substring(0, 8) + "…"));
    }

    @Test
    void expireAllClearsActiveInvitesAndKeepsTtl() throws Exception {
        registerBootstrapOwner();
        String ownerToken = login("first-owner");
        mockMvc.perform(put("/api/server/invite-settings").header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON).content("{\"ttlMinutes\":300}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ttlMinutes").value(300));
        createInvite(ownerToken);
        createInvite(ownerToken);

        mockMvc.perform(post("/api/invites/expire-all").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/invites").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ttlMinutes").value(300))
                .andExpect(jsonPath("$.invites.length()").value(0));
    }

    @Test
    void ttlStampsMintTimeAndReschedulesRetroactively() throws Exception {
        registerBootstrapOwner();
        String ownerToken = login("first-owner");

        mockMvc.perform(put("/api/server/invite-settings").header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON).content("{\"ttlMinutes\":120}"))
                .andExpect(status().isOk());
        String secret = createInvite(ownerToken);
        Invite minted = inviteRepository.findBySecret(secret).orElseThrow();
        assertTrue(Duration.between(minted.getCreatedAt().plus(Duration.ofMinutes(120)), minted.getExpiresAt()).abs()
                        .compareTo(Duration.ofSeconds(5)) < 0,
                "mint expiry = created + 120 min");

        // The retroactive change: every ACTIVE invite moves to createdAt + 300 min.
        mockMvc.perform(put("/api/server/invite-settings").header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON).content("{\"ttlMinutes\":300}"))
                .andExpect(status().isOk());
        Invite rescheduled = inviteRepository.findBySecret(secret).orElseThrow();
        assertEquals(minted.getCreatedAt().plus(Duration.ofMinutes(300)), rescheduled.getExpiresAt(),
                "the active invite is rescheduled to createdAt + 300 min");
    }

    @Test
    void ttlValidationRejectsOutOfRange() throws Exception {
        registerBootstrapOwner();
        String ownerToken = login("first-owner");

        mockMvc.perform(put("/api/server/invite-settings").header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON).content("{\"ttlMinutes\":0}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/server/invite-settings").header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON).content("{\"ttlMinutes\":525601}")) // over the one-year cap
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/server/invite-settings").header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nicknameConflictReturns409() throws Exception {
        registerBootstrapOwner();
        String ownerToken = login("first-owner");
        String secret = createInvite(ownerToken);
        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(regBody("carol-holder", "supersecret", "carol", secret)))
                .andExpect(status().isCreated());

        String secondSecret = createInvite(ownerToken);
        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(regBody("carol-claimer", "supersecret", "carol", secondSecret)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Nickname is already taken"));
    }

    @Test
    void loginCollidingWithSomeonesNicknameReturnsHonestNickname409() throws Exception {
        registerBootstrapOwner();
        String ownerToken = login("first-owner");
        String secret = createInvite(ownerToken);
        // "carol" is a NICKNAME here, not a login — a later registration wanting the login
        // "carol" used to die on UNIQUE(nickname) with a misleading "Username already exists".
        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(regBody("carol-holder", "supersecret", "carol", secret)))
                .andExpect(status().isCreated());

        String secondSecret = createInvite(ownerToken);
        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(regBody("carol", "supersecret", null, secondSecret)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Nickname is already taken"));
    }

    // --- helpers ---

    /** The empty-instance bootstrap: the first user, no secret, becomes the OWNER. */
    private void registerBootstrapOwner() throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(regBody("first-owner", "supersecret", null, null)))
                .andExpect(status().isCreated());
    }

    private String login(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType(JSON)
                        .content(String.format("{\"username\":\"%s\",\"password\":\"%s\"}", username, "supersecret")))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    /** Registers a SECOND member through a freshly minted invite and returns their login token. */
    private String registerAndLoginInvited(String username) throws Exception {
        String ownerToken = login("first-owner");
        String secret = createInvite(ownerToken);
        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(regBody(username, "supersecret", null, secret)))
                .andExpect(status().isCreated());
        return login(username);
    }

    /** POST /api/invites; returns the RAW secret (cut from the assembled link). */
    private String createInvite(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/invites").header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();
        String link = JsonPath.read(result.getResponse().getContentAsString(), "$.link");
        return link.substring(link.indexOf("=") + 1);
    }

    private static String regBody(String username, String password, String nickname, String inviteSecret) {
        StringBuilder body = new StringBuilder("{\"username\":\"").append(username)
                .append("\",\"password\":\"").append(password).append("\"");
        if (nickname != null) {
            body.append(",\"nickname\":\"").append(nickname).append("\"");
        }
        if (inviteSecret != null) {
            body.append(",\"inviteSecret\":\"").append(inviteSecret).append("\"");
        }
        return body.append("}").toString();
    }
}
