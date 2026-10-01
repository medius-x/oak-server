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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The ownership transfer lifecycle: only the owner offers, the recipient (and nobody
 * else) accepts or declines, a second offer replaces the live one, accept moves the
 * crown while the previous owner keeps their other roles, a pending offer survives
 * refetches and dies when either party is banned or removed. Mirrors
 * {@code ChannelCategoryIntegrationTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class OwnershipTransferIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestInvites testInvites;

    @Test
    void offerIsGatedAndThePendingSnapshotIsScopedToItsParties() throws Exception {
        String ownerToken = registerAndLogin("owalice", "supersecret");
        String recipientToken = registerAndLogin("owbob", "supersecret");
        String strangerToken = registerAndLogin("owcarol", "supersecret");
        Long recipientId = memberIdOf(ownerToken, "owbob");

        // a non-owner cannot offer
        mockMvc.perform(post("/api/ownership/offer").header("Authorization", "Bearer " + strangerToken)
                        .contentType(JSON).content(String.format("{\"toUserId\":%d}", recipientId)))
                .andExpect(status().isForbidden());

        // the owner cannot offer to themselves
        Long ownerId = memberIdOf(ownerToken, "owalice");
        mockMvc.perform(post("/api/ownership/offer").header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON).content(String.format("{\"toUserId\":%d}", ownerId)))
                .andExpect(status().isBadRequest());

        // the happy offer
        MvcResult offered = mockMvc.perform(post("/api/ownership/offer").header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON).content(String.format("{\"toUserId\":%d}", recipientId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.toUsername").value("owbob"))
                .andReturn();
        Long transferId = ((Number) JsonPath.read(offered.getResponse().getContentAsString(), "$.id")).longValue();

        // the snapshot is party-scoped and repeatable (a pending offer persists)
        mockMvc.perform(get("/api/ownership/pending").header("Authorization", "Bearer " + recipientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending.id").value(transferId))
                .andExpect(jsonPath("$.pending.fromUsername").value("owalice"));
        mockMvc.perform(get("/api/ownership/pending").header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").doesNotExist());
    }

    @Test
    void aSecondOfferReplacesTheLiveOne() throws Exception {
        String ownerToken = registerAndLogin("owdave", "supersecret");
        String firstToken = registerAndLogin("owerin", "supersecret");
        registerAndLogin("owfrank", "supersecret");
        Long firstId = memberIdOf(ownerToken, "owerin");
        Long secondId = memberIdOf(ownerToken, "owfrank");

        mockMvc.perform(post("/api/ownership/offer").header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON).content(String.format("{\"toUserId\":%d}", firstId)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/ownership/offer").header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON).content(String.format("{\"toUserId\":%d}", secondId)))
                .andExpect(status().isCreated());

        // the first recipient's pending went away with the replacement
        mockMvc.perform(get("/api/ownership/pending").header("Authorization", "Bearer " + firstToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").doesNotExist());
        mockMvc.perform(get("/api/ownership/pending").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending.toUsername").value("owfrank"));
    }

    @Test
    void acceptMovesTheCrownAndTheOldOwnerKeepsTheirRoles() throws Exception {
        String ownerToken = registerAndLogin("owgrace", "supersecret");
        String recipientToken = registerAndLogin("owheidi", "supersecret");
        String strangerToken = registerAndLogin("owivan", "supersecret");
        Long recipientId = memberIdOf(ownerToken, "owheidi");
        Long ownerId = memberIdOf(ownerToken, "owgrace");

        // give the owner a custom role that must survive the transfer
        MvcResult roleCreated = mockMvc.perform(post("/api/roles").header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON).content("{\"name\":\"Keeper\",\"permissions\":[]}"))
                .andExpect(status().isCreated()).andReturn();
        Long keeperRoleId = ((Number) JsonPath.read(roleCreated.getResponse().getContentAsString(), "$.id")).longValue();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/members/" + ownerId + "/roles")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON).content(String.format("{\"roleIds\":[%d]}", keeperRoleId)))
                .andExpect(status().isOk());

        Long transferId = offer(ownerToken, recipientId);

        // nobody but the recipient may accept
        mockMvc.perform(post("/api/ownership/" + transferId + "/accept").header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/ownership/" + transferId + "/accept").header("Authorization", "Bearer " + recipientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        // the crown moved; the old owner kept the custom role
        mockMvc.perform(get("/api/members").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.userId==" + ownerId + ")].owner", org.hamcrest.Matchers.hasItem(false)))
                .andExpect(jsonPath("$[?(@.userId==" + ownerId + ")].roleIds[*]",
                        org.hamcrest.Matchers.hasItem(keeperRoleId.intValue())))
                .andExpect(jsonPath("$[?(@.userId==" + recipientId + ")].owner", org.hamcrest.Matchers.hasItem(true)));

        // the settled offer is closed for everyone
        mockMvc.perform(post("/api/ownership/" + transferId + "/accept").header("Authorization", "Bearer " + recipientToken))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/ownership/pending").header("Authorization", "Bearer " + recipientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").doesNotExist());

        // the NEW owner can offer it back (the old one cannot — 403)
        mockMvc.perform(post("/api/ownership/offer").header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON).content(String.format("{\"toUserId\":%d}", recipientId)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/ownership/offer").header("Authorization", "Bearer " + recipientToken)
                        .contentType(JSON).content(String.format("{\"toUserId\":%d}", ownerId)))
                .andExpect(status().isCreated());
    }

    @Test
    void declineAndCancelCloseTheOffer() throws Exception {
        String ownerToken = registerAndLogin("owjudy", "supersecret");
        String recipientToken = registerAndLogin("owken", "supersecret");
        String strangerToken = registerAndLogin("owliam", "supersecret");
        Long recipientId = memberIdOf(ownerToken, "owken");

        // decline: only the recipient
        Long declined = offer(ownerToken, recipientId);
        mockMvc.perform(post("/api/ownership/" + declined + "/decline").header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/ownership/" + declined + "/decline").header("Authorization", "Bearer " + recipientToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/ownership/" + declined + "/accept").header("Authorization", "Bearer " + recipientToken))
                .andExpect(status().isConflict());

        // cancel: only the sender
        Long cancelled = offer(ownerToken, recipientId);
        mockMvc.perform(post("/api/ownership/" + cancelled + "/cancel").header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/ownership/" + cancelled + "/cancel").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/ownership/pending").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").doesNotExist());
    }

    @Test
    void removalAndBanCancelAPendingOffer() throws Exception {
        String ownerToken = registerAndLogin("owmia", "supersecret");
        registerAndLogin("ownick", "supersecret");
        registerAndLogin("owolga", "supersecret");
        Long removedId = memberIdOf(ownerToken, "ownick");
        Long bannedId = memberIdOf(ownerToken, "owolga");

        // removing the recipient kills the offer
        offer(ownerToken, removedId);
        mockMvc.perform(post("/api/members/" + removedId + "/remove").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/ownership/pending").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").doesNotExist());

        // banning the recipient kills the next offer too (and unban does not revive it)
        offer(ownerToken, bannedId);
        mockMvc.perform(post("/api/members/" + bannedId + "/ban").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/ownership/pending").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").doesNotExist());
    }

    private Long offer(String ownerToken, Long toUserId) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/ownership/offer")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON).content(String.format("{\"toUserId\":%d}", toUserId)))
                .andExpect(status().isCreated()).andReturn();
        return ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private String registerAndLogin(String username, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(testInvites.registrationBody(username, password)))
                .andExpect(status().isCreated());
        MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(JSON)
                        .content(String.format("{\"username\":\"%s\",\"password\":\"%s\"}", username, password)))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(login.getResponse().getContentAsString(), "$.token");
    }

    /** The id of the member whose LOGIN matches. */
    private Long memberIdOf(String token, String username) throws Exception {
        MvcResult members = mockMvc.perform(get("/api/members").header("Authorization", "Bearer " + token))
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
