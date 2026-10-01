package oak.server;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Remove from server" (the reworked kick): the account is flagged removed and ANONYMIZED
 * (login → {@code deleted_<id>}, nickname → {@code Deleted-User-<id>}), so the original
 * login and nickname are freed for a fresh registration while the old messages keep their
 * true author (the wire's {@code authorUsername} becomes the tombstone login — an
 * impersonator registering under the freed name can never inherit the authorship). Removal
 * kills the live access token (401) and the refresh family, is refused for the OWNER, and
 * ban/unban refuse an already-removed member. Mirrors {@code ChannelCategoryIntegrationTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MemberRemovalIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestInvites testInvites;

    @Test
    void removalAnonymizesTheAccountAndFreesTheNames() throws Exception {
        String ownerToken = registerAndLogin("rmalice", "supersecret");
        String victimToken = registerAndLogin("rmbob", "supersecret");
        Long victimId = idOfFirstMemberMatching(ownerToken, "rmbob");
        Long channelId = createChannel(ownerToken, "rm-talk", "TEXT");

        // the victim authors a message — it must keep pointing at the tombstone after removal
        mockMvc.perform(post("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + victimToken)
                        .contentType(JSON).content("{\"content\":\"last words\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/members/" + victimId + "/remove")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        // dropped from the live member list, present in the moderation list with removed=true
        mockMvc.perform(get("/api/members").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.userId==" + victimId + ")]").isEmpty());
        mockMvc.perform(get("/api/members/bans").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.userId==" + victimId + ")].removed",
                        org.hamcrest.Matchers.hasItem(true)))
                .andExpect(jsonPath("$[?(@.userId==" + victimId + ")].nickname",
                        org.hamcrest.Matchers.hasItem("Deleted-User-" + victimId)));

        // still listed by the display roster (authorship resolution) under the tombstone login
        mockMvc.perform(get("/api/users").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id==" + victimId + ")].username",
                        org.hamcrest.Matchers.hasItem("deleted_" + victimId)));

        // the old message's author is the tombstone login, NOT the freed name
        MvcResult fresh = mockMvc.perform(get("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk()).andReturn();
        assertThat((String) JsonPath.read(fresh.getResponse().getContentAsString(), "$[0].authorUsername"))
                .isEqualTo("deleted_" + victimId);

        // the freed login AND nickname register cleanly — and the new account is NOT the old author
        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(testInvites.registrationBody("rmbob", "supersecret")))
                .andExpect(status().isCreated());
        MvcResult reHistory = mockMvc.perform(get("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk()).andReturn();
        assertThat((String) JsonPath.read(reHistory.getResponse().getContentAsString(), "$[0].authorUsername"))
                .isEqualTo("deleted_" + victimId);
    }

    @Test
    void removalKillsTheLiveSessionTokens() throws Exception {
        String ownerToken = registerAndLogin("rmcarol", "supersecret");
        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(testInvites.registrationBody("rmdave", "supersecret")))
                .andExpect(status().isCreated());
        MvcResult victimLogin = loginRaw("rmdave", "supersecret");
        String victimToken = JsonPath.read(victimLogin.getResponse().getContentAsString(), "$.token");
        String victimRefresh = JsonPath.read(victimLogin.getResponse().getContentAsString(), "$.refreshToken");
        Long victimId = idOfFirstMemberMatching(ownerToken, "rmdave");

        mockMvc.perform(post("/api/members/" + victimId + "/remove")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        // the still-valid access JWT is 401'd by the filter (removed, not banned)
        mockMvc.perform(get("/api/channels").header("Authorization", "Bearer " + victimToken))
                .andExpect(status().isUnauthorized());
        // the refresh family is revoked — no token rotation for a removed account
        mockMvc.perform(post("/api/auth/refresh").contentType(JSON)
                        .content(String.format("{\"refreshToken\":\"%s\"}", victimRefresh)))
                .andExpect(status().isUnauthorized());
        // and the old login no longer authenticates
        mockMvc.perform(post("/api/auth/login").contentType(JSON)
                        .content(loginBody("rmdave", "supersecret")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void removalIsGatedAndNeverAppliesToTheOwner() throws Exception {
        String ownerToken = registerAndLogin("rmerin", "supersecret");
        String plebToken = registerAndLogin("rmfrank", "supersecret");
        Long ownerId = idOfFirstMemberMatching(ownerToken, "rmerin");

        // a plain member cannot remove anyone
        mockMvc.perform(post("/api/members/" + ownerId + "/remove")
                        .header("Authorization", "Bearer " + plebToken))
                .andExpect(status().isForbidden());

        // the OWNER cannot be removed (there must always be one)
        mockMvc.perform(post("/api/members/" + ownerId + "/remove")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("The owner cannot be removed"));

        // an unknown id stays a 404
        mockMvc.perform(post("/api/members/999999/remove")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void banAndUnbanRefuseARemovedMember() throws Exception {
        String ownerToken = registerAndLogin("rmgrace", "supersecret");
        registerAndLogin("rmheidi", "supersecret");
        Long victimId = idOfFirstMemberMatching(ownerToken, "rmheidi");

        mockMvc.perform(post("/api/members/" + victimId + "/remove")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/members/" + victimId + "/ban")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This member was removed, not banned"));

        mockMvc.perform(delete("/api/members/" + victimId + "/ban")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void tombstoneCollisionRetriesWithASuffix() throws Exception {
        String ownerToken = registerAndLogin("rmivan", "supersecret");
        registerAndLogin("rmjudy", "supersecret");
        Long victimId = idOfFirstMemberMatching(ownerToken, "rmjudy");

        // someone already sits on the exact tombstone login the removal would pick
        registerAndLogin("deleted_" + victimId, "supersecret");

        mockMvc.perform(post("/api/members/" + victimId + "/remove")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/users").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id==" + victimId + ")]").isNotEmpty())
                .andExpect(jsonPath("$[?(@.id==" + victimId + ")].username",
                        org.hamcrest.Matchers.hasItem("deleted_" + victimId + "-2")))
                .andExpect(jsonPath("$[?(@.id==" + victimId + ")].displayName",
                        org.hamcrest.Matchers.hasItem("Deleted-User-" + victimId + "-2")));
    }

    private String registerAndLogin(String username, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(testInvites.registrationBody(username, password)))
                .andExpect(status().isCreated());
        return JsonPath.read(loginRaw(username, password).getResponse().getContentAsString(), "$.token");
    }

    private MvcResult loginRaw(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(JSON)
                        .content(loginBody(username, password)))
                .andExpect(status().isOk()).andReturn();
    }

    private static String loginBody(String username, String password) {
        return String.format("{\"username\":\"%s\",\"password\":\"%s\"}", username, password);
    }

    private Long createChannel(String token, String name, String type) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/channels")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(String.format("{\"name\":\"%s\",\"type\":\"%s\"}", name, type)))
                .andExpect(status().isCreated()).andReturn();
        return ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();
    }

    /** The id of the member whose LOGIN matches — nickname collisions with tombstones are irrelevant. */
    private Long idOfFirstMemberMatching(String token, String username) throws Exception {
        MvcResult members = mockMvc.perform(get("/api/members")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        List<java.util.Map<String, Object>> rows =
                JsonPath.read(members.getResponse().getContentAsString(), "$");
        for (java.util.Map<String, Object> row : rows) {
            if (username.equals(row.get("username"))) {
                return ((Number) row.get("userId")).longValue();
            }
        }
        throw new AssertionError("member not found: " + username);
    }
}
