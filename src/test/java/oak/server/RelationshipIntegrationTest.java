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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc coverage for the social-graph endpoints on the in-memory H2 test profile: friend requests
 * (pending in/out), accept, decline/cancel, unfriend, block/unblock, mutual-request auto-accept, and
 * the self/block guards. STOMP pushes are not exercised here (MockMvc has no WebSocket layer); the
 * REST state after each mutation is asserted instead.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RelationshipIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestInvites testInvites;

    @Test
    void friendRequestAppearsAsPendingForBothSides() throws Exception {
        String alice = register("alice");
        String bob = register("bob");
        long bobId = userId(bob);

        mockMvc.perform(post("/api/users/me/friends").header("Authorization", "Bearer " + alice)
                        .contentType(JSON).content("{\"username\":\"bob\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("PENDING_OUTGOING"));

        assertEquals("PENDING_OUTGOING", relationshipType(alice, "bob"));
        assertEquals("PENDING_INCOMING", relationshipType(bob, "alice"));
    }

    @Test
    void acceptMakesBothSidesFriends() throws Exception {
        String alice = register("alice");
        String bob = register("bob");
        long aliceId = userId(alice);

        mockMvc.perform(post("/api/users/me/friends").header("Authorization", "Bearer " + alice)
                        .contentType(JSON).content("{\"username\":\"bob\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(put("/api/users/me/friends/" + aliceId).header("Authorization", "Bearer " + bob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("FRIEND"));

        assertEquals("FRIEND", relationshipType(alice, "bob"));
        assertEquals("FRIEND", relationshipType(bob, "alice"));
    }

    @Test
    void decliningRemovesThePendingRequest() throws Exception {
        String alice = register("alice");
        String bob = register("bob");
        long aliceId = userId(alice);

        mockMvc.perform(post("/api/users/me/friends").header("Authorization", "Bearer " + alice)
                        .contentType(JSON).content("{\"username\":\"bob\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/api/users/me/friends/" + aliceId).header("Authorization", "Bearer " + bob))
                .andExpect(status().isNoContent());

        assertNull(relationshipType(alice, "bob"));
        assertNull(relationshipType(bob, "alice"));
    }

    @Test
    void cancelingOutgoingRequestRemovesIt() throws Exception {
        String alice = register("alice");
        register("bob");
        long bobId = userId(login("bob"));

        mockMvc.perform(post("/api/users/me/friends").header("Authorization", "Bearer " + alice)
                        .contentType(JSON).content("{\"username\":\"bob\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/api/users/me/friends/" + bobId).header("Authorization", "Bearer " + alice))
                .andExpect(status().isNoContent());

        assertNull(relationshipType(alice, "bob"));
    }

    @Test
    void unfriendRemovesTheFriendship() throws Exception {
        String alice = register("alice");
        String bob = register("bob");
        long bobId = userId(bob);
        becomeFriends(alice, bob);

        mockMvc.perform(delete("/api/users/me/friends/" + bobId).header("Authorization", "Bearer " + alice))
                .andExpect(status().isNoContent());

        assertNull(relationshipType(alice, "bob"));
        assertNull(relationshipType(bob, "alice"));
    }

    @Test
    void blockPreventsFriendRequestInBothDirections() throws Exception {
        String alice = register("alice");
        String bob = register("bob");

        mockMvc.perform(post("/api/users/me/blocks").header("Authorization", "Bearer " + alice)
                        .contentType(JSON).content("{\"username\":\"bob\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("BLOCKED"));

        // alice cannot re-add bob, and bob cannot add alice either.
        mockMvc.perform(post("/api/users/me/friends").header("Authorization", "Bearer " + alice)
                        .contentType(JSON).content("{\"username\":\"bob\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/users/me/friends").header("Authorization", "Bearer " + bob)
                        .contentType(JSON).content("{\"username\":\"alice\"}"))
                .andExpect(status().isForbidden());

        assertEquals("BLOCKED", relationshipType(alice, "bob"));
    }

    @Test
    void mutualRequestAutoAccepts() throws Exception {
        String alice = register("alice");
        String bob = register("bob");

        // bob requests alice first ...
        mockMvc.perform(post("/api/users/me/friends").header("Authorization", "Bearer " + bob)
                        .contentType(JSON).content("{\"username\":\"alice\"}"))
                .andExpect(status().isCreated());

        // ... then alice requests bob → auto-accept, both friends immediately.
        mockMvc.perform(post("/api/users/me/friends").header("Authorization", "Bearer " + alice)
                        .contentType(JSON).content("{\"username\":\"bob\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("FRIEND"));

        assertEquals("FRIEND", relationshipType(alice, "bob"));
        assertEquals("FRIEND", relationshipType(bob, "alice"));
    }

    @Test
    void selfRequestIsRejected() throws Exception {
        String alice = register("alice");
        mockMvc.perform(post("/api/users/me/friends").header("Authorization", "Bearer " + alice)
                        .contentType(JSON).content("{\"username\":\"alice\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownUserReturns404() throws Exception {
        String alice = register("alice");
        mockMvc.perform(post("/api/users/me/friends").header("Authorization", "Bearer " + alice)
                        .contentType(JSON).content("{\"username\":\"nobody\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void unblockIsIdempotent() throws Exception {
        String alice = register("alice");
        register("bob");
        long bobId = userId(login("bob"));

        mockMvc.perform(post("/api/users/me/blocks").header("Authorization", "Bearer " + alice)
                        .contentType(JSON).content("{\"username\":\"bob\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/api/users/me/blocks/" + bobId).header("Authorization", "Bearer " + alice))
                .andExpect(status().isNoContent());
        // second unblock is a no-op but still 204.
        mockMvc.perform(delete("/api/users/me/blocks/" + bobId).header("Authorization", "Bearer " + alice))
                .andExpect(status().isNoContent());

        assertNull(relationshipType(alice, "bob"));
    }

    @Test
    void acceptWithoutPendingRequestReturns404() throws Exception {
        String alice = register("alice");
        register("bob");
        long aliceId = userId(login("alice"));
        mockMvc.perform(put("/api/users/me/friends/" + aliceId).header("Authorization", "Bearer " + login("bob")))
                .andExpect(status().isNotFound());
    }

    // --- helpers ---

    private void becomeFriends(String requesterToken, String accepterToken) throws Exception {
        long requesterId = userId(requesterToken);
        mockMvc.perform(post("/api/users/me/friends").header("Authorization", "Bearer " + requesterToken)
                        .contentType(JSON).content("{\"username\":\"" + usernameOf(accepterToken) + "\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(put("/api/users/me/friends/" + requesterId).header("Authorization", "Bearer " + accepterToken))
                .andExpect(status().isOk());
    }

    /** The relationship {@code type} (from my view) toward {@code otherUsername}, or null if none. */
    private String relationshipType(String token, String otherUsername) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/users/me/relationships")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        @SuppressWarnings("unchecked")
        List<String> types = JsonPath.read(result.getResponse().getContentAsString(),
                "$[?(@.username=='" + otherUsername + "')].type");
        return types.isEmpty() ? null : types.get(0);
    }

    private long userId(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private String usernameOf(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(JSON))
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.username");
    }

    private String login(String username) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(JSON)
                        .content(String.format("{\"username\":\"%s\",\"password\":\"supersecret\"}", username)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(login.getResponse().getContentAsString(), "$.token");
    }

    private String register(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(testInvites.registrationBody(username, "supersecret")))
                .andExpect(status().isCreated());
        return login(username);
    }
}
