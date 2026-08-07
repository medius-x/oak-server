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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc coverage for group-DM lifecycle on the in-memory H2 profile: creation (caller = owner, all
 * participants, cap), owner-only add, owner-transfer on leave, rename by any participant, owner-only
 * close, and the participant cap on add.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class GroupDmIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createMakesCallerOwnerWithAllParticipants() throws Exception {
        String alice = register("alice");
        register("bob");
        register("carol");
        long aliceId = userId(alice);
        List<Long> others = List.of(userId(login("bob")), userId(login("carol")));

        MvcResult result = createGroupDm(alice, others, "team")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("GROUP_DM"))
                .andExpect(jsonPath("$.ownerId").value(aliceId))
                .andReturn();
        @SuppressWarnings("unchecked")
        List<String> usernames = JsonPath.read(result.getResponse().getContentAsString(), "$.participants[*].username");
        assertTrue(usernames.containsAll(List.of("alice", "bob", "carol")));
    }

    @Test
    void onlyOwnerCanAddParticipant() throws Exception {
        String alice = register("alice");
        String bob = register("bob");
        register("carol");
        register("dave");
        long groupId = id(createGroupDm(alice, List.of(userId(bob), userId(login("carol"))), "g").andReturn());
        long daveId = userId(login("dave"));

        // owner (alice) adds dave → 200
        mockMvc.perform(post("/api/channels/" + groupId + "/participants").header("Authorization", "Bearer " + alice)
                        .contentType(JSON).content("{\"userId\":" + daveId + "}"))
                .andExpect(status().isOk());
        // non-owner (bob) tries to add dave → 403
        mockMvc.perform(post("/api/channels/" + groupId + "/participants").header("Authorization", "Bearer " + bob)
                        .contentType(JSON).content("{\"userId\":" + daveId + "}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void ownerLeavingTransfersOwnership() throws Exception {
        String alice = register("alice");
        String bob = register("bob");
        register("carol");
        long aliceId = userId(alice);
        long bobId = userId(bob);
        long carolId = userId(login("carol"));
        long groupId = id(createGroupDm(alice, List.of(bobId, carolId), "g").andReturn());

        // alice (owner) leaves
        mockMvc.perform(delete("/api/channels/" + groupId + "/participants/" + aliceId).header("Authorization", "Bearer " + alice))
                .andExpect(status().isNoContent());

        // ownership transferred to a remaining participant (bob or carol), not alice
        Long newOwner = ownerIdOf(bob, groupId);
        assertTrue(newOwner.equals(bobId) || newOwner.equals(carolId), "owner should transfer to a remaining member");
    }

    @Test
    void anyParticipantCanRename() throws Exception {
        String alice = register("alice");
        String bob = register("bob");
        register("carol");
        long groupId = id(createGroupDm(alice, List.of(userId(bob), userId(login("carol"))), "old").andReturn());

        mockMvc.perform(patch("/api/channels/" + groupId).header("Authorization", "Bearer " + bob)
                        .contentType(JSON).content("{\"name\":\"new-name\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("new-name"));
    }

    @Test
    void closeRequiresOwnerForGroupDm() throws Exception {
        String alice = register("alice");
        String bob = register("bob");
        register("carol");
        long groupId = id(createGroupDm(alice, List.of(userId(bob), userId(login("carol"))), "g").andReturn());

        // non-owner (bob) cannot close → 403
        mockMvc.perform(delete("/api/channels/dm/" + groupId).header("Authorization", "Bearer " + bob))
                .andExpect(status().isForbidden());
        // owner (alice) closes → 204, and the group disappears from alice's list
        mockMvc.perform(delete("/api/channels/dm/" + groupId).header("Authorization", "Bearer " + alice))
                .andExpect(status().isNoContent());
        MvcResult result = mockMvc.perform(get("/api/channels/dm").header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk()).andReturn();
        assertEquals(0, ((List<?>) JsonPath.read(result.getResponse().getContentAsString(), "$[?(@.id==" + groupId + ")]")).size());
    }

    @Test
    void capAtCreationIsEnforced() throws Exception {
        String alice = register("alice");
        List<Long> ten = new ArrayList<>();
        for (int index = 1; index <= 10; index++) {
            ten.add(userId(register("u" + (index + 10))));   // u11..u20 — usernames are @Size(min=3)
        }
        createGroupDm(alice, ten.subList(0, 9), "nine").andExpect(status().isCreated());   // 1 + 9 = 10 OK
        createGroupDm(alice, ten, "ten").andExpect(status().isBadRequest());               // 1 + 10 = 11 too many
    }

    @Test
    void addingBeyondCapIsRejected() throws Exception {
        String alice = register("alice");
        List<Long> nine = new ArrayList<>();
        for (int index = 1; index <= 9; index++) {
            nine.add(userId(register("u" + (index + 10))));   // u11..u19 — usernames are @Size(min=3)
        }
        long groupId = id(createGroupDm(alice, nine, "full").andReturn());                 // 1 + 9 = 10 (full)
        long eleventh = userId(register("u30"));

        mockMvc.perform(post("/api/channels/" + groupId + "/participants").header("Authorization", "Bearer " + alice)
                        .contentType(JSON).content("{\"userId\":" + eleventh + "}"))
                .andExpect(status().isBadRequest());
    }

    // --- helpers ---

    private Long ownerIdOf(String token, long groupId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/channels/dm").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        @SuppressWarnings("unchecked")
        List<Number> ids = JsonPath.read(result.getResponse().getContentAsString(),
                "$[?(@.id==" + groupId + ")].ownerId");
        return ids.isEmpty() ? null : ids.get(0).longValue();
    }

    private ResultActions createGroupDm(String token, List<Long> participantIds, String name) throws Exception {
        String ids = participantIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        String body = "{\"participantIds\":[" + ids + "],\"name\":\"" + name + "\"}";
        return mockMvc.perform(post("/api/channels/group-dm").header("Authorization", "Bearer " + token)
                .contentType(JSON).content(body));
    }

    private long id(MvcResult result) throws Exception {
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private long userId(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private String login(String username) throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login").contentType(JSON)
                        .content(String.format("{\"username\":\"%s\",\"password\":\"supersecret\"}", username)))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(loginResult.getResponse().getContentAsString(), "$.token");
    }

    private String register(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(String.format("{\"username\":\"%s\",\"password\":\"supersecret\"}", username)))
                .andExpect(status().isCreated());
        return login(username);
    }
}
