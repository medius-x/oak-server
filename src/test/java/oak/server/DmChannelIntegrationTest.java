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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc coverage for the 1:1 DM endpoints on the in-memory H2 profile: opening a DM returns a channel
 * with both participants, reopening the same pair deduplicates (same id), a block prevents opening, and
 * {@code listMine} returns the caller's DMs. Message read/send gating and the public-list exclusion are
 * covered by {@link DmPlumbingIntegrationTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DmChannelIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void openingDmReturnsChannelWithBothParticipants() throws Exception {
        String alice = register("alice");
        register("bob");
        long bobId = userId(login("bob"));

        MvcResult result = createDm(alice, bobId);
        @SuppressWarnings("unchecked")
        List<String> usernames = JsonPath.read(result.getResponse().getContentAsString(), "$.participants[*].username");
        assertTrue(usernames.containsAll(List.of("alice", "bob")));
        assertEquals("DM", JsonPath.read(result.getResponse().getContentAsString(), "$.type"));
    }

    @Test
    void reopeningTheSamePairDeduplicates() throws Exception {
        String alice = register("alice");
        register("bob");
        long bobId = userId(login("bob"));

        long first = id(createDm(alice, bobId));
        long second = id(createDm(alice, bobId));
        assertEquals(first, second);
    }

    @Test
    void blockPreventsOpeningDm() throws Exception {
        String alice = register("alice");
        register("bob");
        long bobId = userId(login("bob"));

        mockMvc.perform(post("/api/users/me/blocks").header("Authorization", "Bearer " + alice)
                        .contentType(JSON).content("{\"username\":\"bob\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/channels/dm").header("Authorization", "Bearer " + alice)
                        .contentType(JSON).content("{\"recipientId\":" + bobId + "}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void listMineReturnsOnlyMyDms() throws Exception {
        String alice = register("alice");
        register("bob");
        register("carol");
        long bobId = userId(login("bob"));
        long carolId = userId(login("carol"));

        createDm(alice, bobId);
        createDm(alice, carolId);

        MvcResult result = mockMvc.perform(get("/api/channels/dm").header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(2, ((List<?>) JsonPath.read(result.getResponse().getContentAsString(), "$[*]")).size());
    }

    // --- helpers ---

    private MvcResult createDm(String token, long recipientId) throws Exception {
        return mockMvc.perform(post("/api/channels/dm").header("Authorization", "Bearer " + token)
                        .contentType(JSON).content("{\"recipientId\":" + recipientId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andReturn();
    }

    private long id(MvcResult result) throws Exception {
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
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
                        .content(String.format("{\"username\":\"%s\",\"password\":\"supersecret\"}", username)))
                .andExpect(status().isCreated());
        return login(username);
    }
}
