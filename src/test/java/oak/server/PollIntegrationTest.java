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

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Polls end to end (2026-11-25): the create gate (SEND_MESSAGES + CREATE_POLLS — @everyone
 * deliberately does NOT carry the create bit, the owner bypasses), the wire shape riding the
 * message, voting with replacement (allowVoteChange) and its 409 twin, single-choice
 * validation, the distinct-PEOPLE voter count, anonymous results carrying no names, the lazy
 * deadline close, the early close's gate and idempotency, and the edit ban on poll messages.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PollIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestInvites testInvites;

    @Autowired
    private oak.server.repository.PollRepository pollRepository;

    @Test
    void ownerCreatesPollAndItRidesTheMessage() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        Long channelId = createChannel(ownerToken, "general", "TEXT");

        MvcResult result = mockMvc.perform(post("/api/channels/" + channelId + "/polls")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON)
                        .content("""
                                {"question":"Which coffee?","options":["Iced","Hot","None"],
                                 "allowMultiple":true,"anonymous":false,"allowVoteChange":true}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.poll.id").isNotEmpty())
                .andReturn();
        Long messageId = ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
        Long pollId = ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.poll.id")).longValue();

        mockMvc.perform(get("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].poll.id").value(pollId.intValue()))
                .andExpect(jsonPath("$[0].poll.question").value("Which coffee?"))
                .andExpect(jsonPath("$[0].poll.allowMultiple").value(true))
                .andExpect(jsonPath("$[0].poll.allowVoteChange").value(true))
                .andExpect(jsonPath("$[0].poll.options.length()").value(3))
                .andExpect(jsonPath("$[0].poll.options[0].text").value("Iced"));
    }

    @Test
    void plebeianWithoutCreatePollsIsForbidden() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        Long channelId = createChannel(ownerToken, "general", "TEXT");
        String plebToken = registerAndLogin("bob", "supersecret");

        mockMvc.perform(post("/api/channels/" + channelId + "/polls")
                        .header("Authorization", "Bearer " + plebToken)
                        .contentType(JSON)
                        .content("{\"question\":\"Q?\",\"options\":[\"a\",\"b\"]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void pollInAVoiceChannelIsRejected() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        Long voiceId = createChannel(ownerToken, "Lobby", "VOICE");
        mockMvc.perform(post("/api/channels/" + voiceId + "/polls")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON)
                        .content("{\"question\":\"Q?\",\"options\":[\"a\",\"b\"]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void multiChoiceVotesTallyPeopleAndReplaceOnRevote() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        Long channelId = createChannel(ownerToken, "general", "TEXT");
        String bobToken = registerAndLogin("bob", "supersecret");
        Long[] ids = createPoll(ownerToken, channelId,
                "{\"question\":\"Q?\",\"options\":[\"a\",\"b\"],\"allowMultiple\":true,\"allowVoteChange\":true}");
        Long pollId = ids[1];
        Long optionA = ids[2];
        Long optionB = ids[3];

        mockMvc.perform(post("/api/polls/" + pollId + "/vote")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON)
                        .content("{\"optionIds\":[" + optionA + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalVoters").value(1));

        mockMvc.perform(post("/api/polls/" + pollId + "/vote")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(JSON)
                        .content("{\"optionIds\":[" + optionA + "," + optionB + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalVoters").value(2)); // PEOPLE, not rows

        // allowVoteChange=true: alice's re-vote replaces her whole selection.
        mockMvc.perform(post("/api/polls/" + pollId + "/vote")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON)
                        .content("{\"optionIds\":[" + optionB + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalVoters").value(2))
                .andExpect(jsonPath("$.options[0].votes").value(1))
                .andExpect(jsonPath("$.options[1].votes").value(2))
                .andExpect(jsonPath("$.options[0].voters[0]").value("bob"));
    }

    @Test
    void singleChoiceRejectsTwoOptionsAndNoChangeRejectsRevote() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        Long channelId = createChannel(ownerToken, "general", "TEXT");
        Long[] ids = createPoll(ownerToken, channelId,
                "{\"question\":\"Q?\",\"options\":[\"a\",\"b\"]}"); // single-choice, no change
        Long pollId = ids[1];

        mockMvc.perform(post("/api/polls/" + pollId + "/vote")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON)
                        .content("{\"optionIds\":[" + ids[2] + "," + ids[3] + "]}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/polls/" + pollId + "/vote")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON)
                        .content("{\"optionIds\":[" + ids[3] + "]}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/polls/" + pollId + "/vote")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON)
                        .content("{\"optionIds\":[" + ids[3] + "]}"))
                .andExpect(status().isConflict());
    }

    @Test
    void anonymousResultsCarryNoVoterNames() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        Long channelId = createChannel(ownerToken, "general", "TEXT");
        Long[] ids = createPoll(ownerToken, channelId,
                "{\"question\":\"Q?\",\"options\":[\"a\",\"b\"],\"anonymous\":true}");
        Long pollId = ids[1];

        mockMvc.perform(post("/api/polls/" + pollId + "/vote")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON)
                        .content("{\"optionIds\":[" + ids[3] + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalVoters").value(1))
                .andExpect(jsonPath("$.options[1].votes").value(1))
                .andExpect(jsonPath("$.options[1].voters.length()").value(0)); // anonymous: no names
    }

    @Test
    void expiredDeadlineClosesLazilyAndRejectsVotes() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        Long channelId = createChannel(ownerToken, "general", "TEXT");
        Long[] ids = createPoll(ownerToken, channelId,
                "{\"question\":\"Q?\",\"options\":[\"a\",\"b\"],\"durationMinutes\":1}");
        Long pollId = ids[1];

        // Wind the deadline into the past directly (the sweeper-free lazy path is under test).
        pollRepository.findById(pollId).orElseThrow().setEndsAt(Instant.now().minusSeconds(60));

        mockMvc.perform(post("/api/polls/" + pollId + "/vote")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON)
                        .content("{\"optionIds\":[" + ids[3] + "]}"))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/polls/" + pollId + "/results")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.poll.closedAt").isNotEmpty());
    }

    @Test
    void earlyCloseIsGatedAndIdempotent() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        Long channelId = createChannel(ownerToken, "general", "TEXT");
        String bobToken = registerAndLogin("bob", "supersecret");
        Long[] ids = createPoll(ownerToken, channelId, "{\"question\":\"Q?\",\"options\":[\"a\",\"b\"]}");
        Long pollId = ids[1];

        mockMvc.perform(post("/api/polls/" + pollId + "/close")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/polls/" + pollId + "/close")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/polls/" + pollId + "/close")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent()); // idempotent

        mockMvc.perform(post("/api/polls/" + pollId + "/vote")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON)
                        .content("{\"optionIds\":[" + ids[3] + "]}"))
                .andExpect(status().isConflict());
    }

    @Test
    void pollMessagesCannotBeEdited() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        Long channelId = createChannel(ownerToken, "general", "TEXT");
        Long[] ids = createPoll(ownerToken, channelId, "{\"question\":\"Q?\",\"options\":[\"a\",\"b\"]}");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/channels/" + channelId + "/messages/" + ids[0])
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON)
                        .content("{\"content\":\"nope\"}"))
                .andExpect(status().isBadRequest());
        assertTrue(pollRepository.findById(ids[1]).isPresent());
    }

    @Test
    void myVotesSnapshotReturnsOnlyTheCallersRows() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        Long channelId = createChannel(ownerToken, "general", "TEXT");
        String bobToken = registerAndLogin("bob", "supersecret");
        String carolToken = registerAndLogin("carol", "supersecret");
        Long[] ids = createPoll(ownerToken, channelId,
                "{\"question\":\"Q?\",\"options\":[\"a\",\"b\"],\"allowMultiple\":true,\"allowVoteChange\":true}");
        Long messageId = ids[0];
        Long pollId = ids[1];
        Long optionA = ids[2];
        Long optionB = ids[3];

        mockMvc.perform(post("/api/polls/" + pollId + "/vote")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(JSON)
                        .content("{\"optionIds\":[" + optionA + "]}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/polls/" + pollId + "/vote")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON)
                        .content("{\"optionIds\":[" + optionB + "]}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/channels/" + channelId + "/polls/my-votes")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].messageId").value(messageId.intValue()))
                .andExpect(jsonPath("$[0].pollId").value(pollId.intValue()))
                .andExpect(jsonPath("$[0].optionIds.length()").value(1))
                .andExpect(jsonPath("$[0].optionIds[0]").value(optionA.intValue()));

        mockMvc.perform(get("/api/channels/" + channelId + "/polls/my-votes")
                        .header("Authorization", "Bearer " + carolToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // --- helpers -----------------------------------------------------------

    /** The first registrant of the @Transactional test is the OWNER bootstrap. */
    private String registerAndLogin(String username, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(testInvites.registrationBody(username, password)))
                .andExpect(status().isCreated());
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType(JSON)
                        .content(String.format("{\"username\":\"%s\",\"password\":\"%s\"}", username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    private Long createChannel(String token, String name, String type) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/channels")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(String.format("{\"name\":\"%s\",\"type\":\"%s\"}", name, type)))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    /** Creates a poll; returns {messageId, pollId, optionA, optionB}. */
    private Long[] createPoll(String token, Long channelId, String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/channels/" + channelId + "/polls")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        String json = result.getResponse().getContentAsString();
        return new Long[]{
                ((Number) JsonPath.read(json, "$.id")).longValue(),
                ((Number) JsonPath.read(json, "$.poll.id")).longValue(),
                ((Number) JsonPath.read(json, "$.poll.options[0].id")).longValue(),
                ((Number) JsonPath.read(json, "$.poll.options[1].id")).longValue(),
        };
    }
}
