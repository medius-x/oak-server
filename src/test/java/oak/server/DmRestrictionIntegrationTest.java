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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc coverage for {@code RESTRICT_PRIVATE_MESSAGES} — the one subtractive permission: a role
 * carrying the bit takes direct messages away from its holders (no sending, no receiving; a 1:1 with
 * a restricted member freezes for BOTH sides) while existing conversations stay readable. Group DMs
 * only silence the restricted member themselves; the bit on @everyone is ignored by the raw-role
 * check; OWNER/ADMINISTRATOR are immune.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DmRestrictionIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;
    private static final String RESTRICT = "RESTRICT_PRIVATE_MESSAGES";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestInvites testInvites;

    @Test
    void restrictedMemberCanNeitherStartNorReceiveADm() throws Exception {
        String owner = register("owner");
        String alice = register("alice");
        register("bob");
        long bobId = userId(login("bob"));
        assignRoles(owner, bobId, createRole(owner, "No-DM", RESTRICT));

        // Alice (clean) cannot open a DM toward the restricted bob...
        postDm(alice, bobId).andExpect(status().isForbidden());
        // ...and bob cannot open one toward anyone.
        postDm(login("bob"), userId(login("alice"))).andExpect(status().isForbidden());
    }

    @Test
    void existingOneToOneFreezesForBothSidesButStaysReadable() throws Exception {
        String owner = register("owner");
        String alice = register("alice");
        register("bob");
        long bobId = userId(login("bob"));
        long dmId = dmId(postDm(alice, bobId));
        long messageId = messageOf(postMessage(alice, dmId, "before the restriction"));

        assignRoles(owner, bobId, createRole(owner, "No-DM", RESTRICT));

        // Frozen for BOTH sides — a message to a restricted member would be a received one.
        postMessage(alice, dmId, "after").andExpect(status().isForbidden());
        postMessage(login("bob"), dmId, "after").andExpect(status().isForbidden());
        mockMvc.perform(post("/api/channels/" + dmId + "/messages/forward").header("Authorization", "Bearer " + alice)
                        .contentType(JSON).content("{\"sourceMessageId\":" + messageId + "}"))
                .andExpect(status().isForbidden());

        // Reads stay open: history, the DM list, and read-state ack.
        mockMvc.perform(get("/api/channels/" + dmId + "/messages").header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(messageId));
        mockMvc.perform(get("/api/channels/dm").header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(dmId));
        mockMvc.perform(post("/api/channels/" + dmId + "/read").header("Authorization", "Bearer " + alice))
                .andExpect(status().isNoContent());
    }

    @Test
    void groupDmSilencesOnlyTheRestrictedMember() throws Exception {
        String owner = register("owner");
        String alice = register("alice");
        register("bob");
        register("carol");
        register("dave");
        long bobId = userId(login("bob"));
        long daveId = userId(login("dave"));

        // The group predates the restriction; bob is a member from the start.
        long groupId = groupOf(postGroupDm(alice, userId(login("carol")), bobId));
        assignRoles(owner, bobId, createRole(owner, "No-DM", RESTRICT));

        postMessage(login("bob"), groupId, "muted").andExpect(status().isForbidden());
        postMessage(alice, groupId, "still alive").andExpect(status().isCreated());

        // A restricted member cannot enter a NEW group...
        postGroupDm(alice, userId(login("carol")), bobId).andExpect(status().isForbidden());
        // ...nor be added to an existing one (dave gets restricted too, then add attempt 403s).
        assignRoles(owner, daveId, createRole(owner, "Also-No-DM", RESTRICT));
        mockMvc.perform(post("/api/channels/" + groupId + "/participants").header("Authorization", "Bearer " + alice)
                        .contentType(JSON).content("{\"userId\":" + daveId + "}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void ownerAndAdministratorAreImmune() throws Exception {
        String owner = register("owner"); // the first registrant
        String carol = register("carol");
        register("dave");
        long carolId = userId(login("carol"));
        long daveId = userId(login("dave"));

        // OWNER holding the restricting role (self-assigned) stays unrestricted.
        assignRoles(owner, userId(owner), createRole(owner, "No-DM", RESTRICT));
        postDm(owner, daveId).andExpect(status().isOk());

        // An ADMINISTRATOR carrying the restricting role on top stays unrestricted too.
        long adminRole = createRole(owner, "Admins", "ADMINISTRATOR");
        long noDmRole = createRole(owner, "Admin-No-DM", RESTRICT);
        mockMvc.perform(put("/api/members/" + carolId + "/roles").header("Authorization", "Bearer " + owner)
                        .contentType(JSON).content("{\"roleIds\":[" + adminRole + "," + noDmRole + "]}"))
                .andExpect(status().isOk());
        postDm(carol, daveId).andExpect(status().isOk());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theBitOnEveryoneRestrictsNoOne() throws Exception {
        String owner = register("owner");
        String alice = register("alice");
        register("bob");
        long bobId = userId(login("bob"));

        // Flip the bit onto the @everyone base row through the same PATCH the editor uses.
        MvcResult rolesResult = mockMvc.perform(get("/api/roles").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk()).andReturn();
        List<Map<String, Object>> roles = JsonPath.read(rolesResult.getResponse().getContentAsString(), "$[*]");
        Map<String, Object> everyone = roles.stream()
                .filter(role -> "EVERYONE".equals(role.get("kind")))
                .findFirst().orElseThrow();
        StringBuilder names = new StringBuilder("[");
        for (String permission : (List<String>) everyone.get("permissions")) {
            names.append('"').append(permission).append("\",");
        }
        names.append('"').append(RESTRICT).append("\"]");
        mockMvc.perform(patch("/api/roles/" + everyone.get("id")).header("Authorization", "Bearer " + owner)
                        .contentType(JSON).content("{\"permissions\":" + names + "}"))
                .andExpect(status().isOk());

        // The raw-role check ignores @everyone — ordinary members still DM freely.
        postDm(alice, bobId).andExpect(status().isOk());
    }

    @Test
    void removingTheRoleUnfreezesWithoutRestart() throws Exception {
        String owner = register("owner");
        String alice = register("alice");
        register("bob");
        long bobId = userId(login("bob"));
        assignRoles(owner, bobId, createRole(owner, "No-DM", RESTRICT));
        postDm(alice, bobId).andExpect(status().isForbidden());

        mockMvc.perform(put("/api/members/" + bobId + "/roles").header("Authorization", "Bearer " + owner)
                        .contentType(JSON).content("{\"roleIds\":[]}"))
                .andExpect(status().isOk());
        postDm(alice, bobId).andExpect(status().isOk());
    }

    @Test
    void wireFlagsCarryTheRestrictionWithoutLeakingTheName() throws Exception {
        String owner = register("owner");
        register("alice");
        register("bob");
        long bobId = userId(login("bob"));
        assignRoles(owner, bobId, createRole(owner, "No-DM", RESTRICT));

        // MyPermissions: the flag is explicit, the bit is NOT among the grant names (an
        // ADMINISTRATOR's ALL mask would otherwise leak it into can()-style client gating).
        mockMvc.perform(get("/api/users/me/permissions").header("Authorization", "Bearer " + login("bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dmRestricted").value(true))
                .andExpect(jsonPath("$.permissions").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(RESTRICT))));

        // MemberResponse: the management roster marks the restricted member.
        MvcResult membersResult = mockMvc.perform(get("/api/members").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk()).andReturn();
        List<Boolean> bobFlag = JsonPath.read(membersResult.getResponse().getContentAsString(),
                "$[?(@.userId == " + bobId + ")].dmRestricted");
        assertTrue(bobFlag.size() == 1 && bobFlag.get(0), "bob's roster row must carry dmRestricted=true");
    }

    // --- helpers ---

    private org.springframework.test.web.servlet.ResultActions postDm(String token, long recipientId) throws Exception {
        return mockMvc.perform(post("/api/channels/dm").header("Authorization", "Bearer " + token)
                .contentType(JSON).content("{\"recipientId\":" + recipientId + "}"));
    }

    private org.springframework.test.web.servlet.ResultActions postGroupDm(String token, long firstId, long secondId)
            throws Exception {
        return mockMvc.perform(post("/api/channels/group-dm").header("Authorization", "Bearer " + token)
                .contentType(JSON).content("{\"participantIds\":[" + firstId + "," + secondId + "]}"));
    }

    private org.springframework.test.web.servlet.ResultActions postMessage(String token, long channelId, String content)
            throws Exception {
        return mockMvc.perform(post("/api/channels/" + channelId + "/messages").header("Authorization", "Bearer " + token)
                .contentType(JSON).content("{\"content\":\"" + content + "\"}"));
    }

    private long messageOf(org.springframework.test.web.servlet.ResultActions send) throws Exception {
        return ((Number) JsonPath.read(send.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }

    private long dmId(org.springframework.test.web.servlet.ResultActions open) throws Exception {
        return ((Number) JsonPath.read(open.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }

    private long groupOf(org.springframework.test.web.servlet.ResultActions create) throws Exception {
        return ((Number) JsonPath.read(create.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }

    private long createRole(String token, String name, String... permissions) throws Exception {
        StringBuilder names = new StringBuilder("[");
        for (String permission : permissions) {
            names.append('"').append(permission).append("\",");
        }
        if (names.length() > 1) {
            names.setLength(names.length() - 1);
        }
        names.append(']');
        MvcResult result = mockMvc.perform(post("/api/roles").header("Authorization", "Bearer " + token)
                        .contentType(JSON).content(String.format("{\"name\":\"%s\",\"permissions\":%s}", name, names)))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private void assignRoles(String token, long userId, long roleId) throws Exception {
        mockMvc.perform(put("/api/members/" + userId + "/roles").header("Authorization", "Bearer " + token)
                        .contentType(JSON).content("{\"roleIds\":[" + roleId + "]}"))
                .andExpect(status().isOk());
    }

    private long userId(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private String login(String username) throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login").contentType(JSON)
                        .content(String.format("{\"username\":\"%s\",\"password\":\"supersecret\"}", username)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(loginResult.getResponse().getContentAsString(), "$.token");
    }

    private String register(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(testInvites.registrationBody(username, "supersecret")))
                .andExpect(status().isCreated());
        return login(username);
    }
}
