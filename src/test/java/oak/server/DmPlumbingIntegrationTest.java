package oak.server;

import com.jayway.jsonpath.JsonPath;
import oak.server.entity.Channel;
import oak.server.entity.ChannelParticipant;
import oak.server.entity.ChannelType;
import oak.server.entity.User;
import oak.server.repository.ChannelParticipantRepository;
import oak.server.repository.ChannelRepository;
import oak.server.repository.UserRepository;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the DM-reuse thesis end-to-end on the in-memory H2 profile: a DM channel is built from
 * {@code ChannelType.DM} + {@code ChannelParticipant} rows (seeded directly via repositories here,
 * since the DM CRUD endpoints land in a later phase), and the single DM branch in
 * {@code AuthorizationService.hasInChannel} makes the existing message endpoints gate correctly —
 * participants read/send, non-participants get 403, DMs never appear in the public channel list, and
 * even the instance OWNER cannot read a DM they are not part of. The SUBSCRIBE-leak closure
 * ({@code DmSubscriptionInterceptor}) is not exercisable via MockMvc (no WebSocket layer) and is
 * covered by the manual smoke test instead.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DmPlumbingIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ChannelRepository channelRepository;
    @Autowired
    private ChannelParticipantRepository participantRepository;
    @Autowired
    private UserRepository userRepository;

    @Test
    void dmChannelIsHiddenFromPublicChannelList() throws Exception {
        register("alice");                 // OWNER
        register("bob");
        long dmId = createDmChannel("alice", "bob");
        long general = createPublicChannel("alice", "general");

        MvcResult result = mockMvc.perform(get("/api/channels").header("Authorization", "Bearer " + login("alice")))
                .andExpect(status().isOk())
                .andReturn();
        @SuppressWarnings("unchecked")
        List<Number> ids = JsonPath.read(result.getResponse().getContentAsString(), "$[*].id");
        assertFalse(ids.stream().anyMatch(id -> id.longValue() == dmId), "DM must not appear in public list");
        assertFalse(ids.stream().noneMatch(id -> id.longValue() == general), "public channel must appear");
    }

    @Test
    void participantCanSendAndReadDmNonParticipantCannot() throws Exception {
        String alice = register("alice");
        register("bob");
        String carol = register("carol");
        long dmId = createDmChannel("alice", "bob");   // participants: alice + bob only

        // alice (participant) sends and reads — both succeed.
        mockMvc.perform(post("/api/channels/" + dmId + "/messages").header("Authorization", "Bearer " + alice)
                        .contentType(JSON).content("{\"content\":\"hi\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/channels/" + dmId + "/messages").header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk());

        // carol (non-participant) cannot send or read — 403 from the membership branch.
        mockMvc.perform(post("/api/channels/" + dmId + "/messages").header("Authorization", "Bearer " + carol)
                        .contentType(JSON).content("{\"content\":\"hi\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/channels/" + dmId + "/messages").header("Authorization", "Bearer " + carol))
                .andExpect(status().isForbidden());
    }

    @Test
    void ownerCannotReadDmTheyAreNotPartOf() throws Exception {
        String alice = register("alice");              // OWNER
        register("bob");
        register("carol");
        long dmId = createDmChannel("bob", "carol");    // alice is NOT a participant

        // The DM branch sits before the ADMINISTRATOR short-circuit, so even OWNER is denied.
        mockMvc.perform(get("/api/channels/" + dmId + "/messages").header("Authorization", "Bearer " + alice))
                .andExpect(status().isForbidden());
    }

    @Test
    void dmEveryoneMentionCountsForParticipant() throws Exception {
        String alice = register("alice");
        register("bob");
        String bob = login("bob");
        long dmId = createDmChannel("alice", "bob");

        mockMvc.perform(post("/api/channels/" + dmId + "/messages").header("Authorization", "Bearer " + alice)
                        .contentType(JSON).content("{\"content\":\"@everyone hi\"}"))
                .andExpect(status().isCreated());

        // bob (participant) sees the message as 1 unread and 1 mention — @everyone is bounded to the
        // DM participants, not the whole instance.
        assertEquals(1, snapshotField(bob, dmId, "unreadCount"));
        assertEquals(1, snapshotField(bob, dmId, "mentionCount"));
    }

    /** Reads one field of the read-state snapshot row for {@code channelId} (fails if the channel is
     *  absent — a participant's snapshot always includes their DM channels). */
    private long snapshotField(String token, long channelId, String field) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/read-states").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        @SuppressWarnings("unchecked")
        List<Number> values = JsonPath.read(result.getResponse().getContentAsString(),
                "$[?(@.channelId==" + channelId + ")]." + field);
        assertFalse(values.isEmpty(), "channel " + channelId + " not present in read-state snapshot");
        return values.get(0).longValue();
    }

    // --- helpers ---

    /** Creates a DM channel with two participants, directly via the repositories. */
    private long createDmChannel(String usernameA, String usernameB) {
        User userA = userRepository.findByUsername(usernameA).orElseThrow();
        User userB = userRepository.findByUsername(usernameB).orElseThrow();
        Channel dm = new Channel("dm", ChannelType.DM);
        dm.setDmKey(dmKey(userA.getId(), userB.getId()));
        channelRepository.save(dm);
        participantRepository.save(new ChannelParticipant(dm, userA));
        participantRepository.save(new ChannelParticipant(dm, userB));
        return dm.getId();
    }

    private long createPublicChannel(String username, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/channels").header("Authorization", "Bearer " + login(username))
                        .contentType(JSON).content("{\"name\":\"" + name + "\",\"type\":\"TEXT\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private static String dmKey(Long first, Long second) {
        long min = Math.min(first, second);
        long max = Math.max(first, second);
        return min + "_" + max;
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
