package oak.server;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import oak.server.repository.UserRepository;
import oak.server.websocket.VoiceMembershipTracker;

import java.util.ArrayList;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc coverage for the Janus room-info endpoint — including the V18 user-limit gate. The test
 * profile's noop gateway keeps the token registry empty, so the occupancy in these tests is driven
 * straight into the STOMP membership tracker.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RoomControllerIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestInvites testInvites;

    @Autowired
    private VoiceMembershipTracker voiceMembership;

    @Autowired
    private UserRepository userRepository;

    /** Tracker sessions entered by a test — the tracker is a process-wide singleton that
     *  survives every @Transactional rollback, so every enter() must be undone by hand. */
    private final List<String> enteredSessions = new ArrayList<>();

    @AfterEach
    void leaveTracker() {
        enteredSessions.forEach(voiceMembership::exit);
        enteredSessions.clear();
    }

    @Test
    void roomRequiresAuth() throws Exception {
        mockMvc.perform(get("/api/channels/1/room")).andExpect(status().isUnauthorized());
    }

    @Test
    void roomReturnsJanusUrlAndRoomId() throws Exception {
        String token = registerAndLogin("alice", "supersecret");
        Long channelId = createVoiceChannel(token, null);

        mockMvc.perform(get("/api/channels/" + channelId + "/room").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.janusUrl").value("http://localhost:8088/janus"))
                .andExpect(jsonPath("$.roomId").value(channelId))
                // noop voice-access service (admin-enabled=false in the test profile)
                .andExpect(jsonPath("$.token").value("test-token"))
                .andExpect(jsonPath("$.pin").value("test-pin"));
    }

    @Test
    void roomNotFoundForMissingChannel() throws Exception {
        String token = registerAndLogin("bob", "supersecret");

        mockMvc.perform(get("/api/channels/999999/room").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void roomRefusesEntryWhenTheUserLimitIsReached() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret"); // OWNER: creates the channel
        String carolToken = registerAndLogin("carol", "supersecret");
        Long channelId = createVoiceChannel(ownerToken, 1);
        // carol takes the one seat the honest way — GET /room passes and mints her token.
        mockMvc.perform(get("/api/channels/" + channelId + "/room").header("Authorization", "Bearer " + carolToken))
                .andExpect(status().isOk());

        String bobToken = registerAndLogin("bob", "supersecret"); // plain member: CONNECT via @everyone
        mockMvc.perform(get("/api/channels/" + channelId + "/room").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isConflict());
    }

    @Test
    void announcingTheJoinMustNotBypassTheLimit() throws Exception {
        // The live bug (2026-11-18): the client sends /app/voice.join BEFORE fetching /room,
        // so by the gate's moment the entrant already sits in the STOMP tracker — a
        // tracker-membership fast-pass let EVERYONE into a full room. The pass-card is the
        // live token, minted only by the gate itself.
        String ownerToken = registerAndLogin("alice", "supersecret");
        String carolToken = registerAndLogin("carol", "supersecret");
        Long channelId = createVoiceChannel(ownerToken, 1);
        mockMvc.perform(get("/api/channels/" + channelId + "/room").header("Authorization", "Bearer " + carolToken))
                .andExpect(status().isOk());
        // bob announces voice.join (tracker entry) but holds NO token — the gate must refuse.
        sitInRoom("bob-announce", "bob", channelId);
        String bobToken = registerAndLogin("bob", "supersecret");

        mockMvc.perform(get("/api/channels/" + channelId + "/room").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isConflict());
    }

    @Test
    void anAnnouncedEntrantStillEntersANonFullRoom() throws Exception {
        // The counter-regression of the fix above: the in-flight announce must not count as
        // the entrant's OWN seat either — a limit-2 room with one member still admits the
        // announced second, and an EMPTY limit-1 room admits its first joiner.
        String ownerToken = registerAndLogin("alice", "supersecret");
        String carolToken = registerAndLogin("carol", "supersecret");
        String bobToken = registerAndLogin("bob", "supersecret");
        Long channelId = createVoiceChannel(ownerToken, 2);
        mockMvc.perform(get("/api/channels/" + channelId + "/room").header("Authorization", "Bearer " + carolToken))
                .andExpect(status().isOk());
        sitInRoom("carol-session", "carol", channelId);
        // bob's announce lands BEFORE his /room (the client's actual order)…
        sitInRoom("bob-session", "bob", channelId);
        mockMvc.perform(get("/api/channels/" + channelId + "/room").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk());

        // …and a fresh limit-1 room admits its very first, announce-first joiner.
        Long soloId = createVoiceChannel(ownerToken, 1);
        String daveToken = registerAndLogin("dave", "supersecret");
        sitInRoom("dave-session", "dave", soloId);
        mockMvc.perform(get("/api/channels/" + soloId + "/room").header("Authorization", "Bearer " + daveToken))
                .andExpect(status().isOk());
    }

    @Test
    void joinFullRoomsPermissionBypassesTheLimit() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        String carolToken = registerAndLogin("carol", "supersecret");
        String bobToken = registerAndLogin("bob", "supersecret");
        Long bobId = userRepository.findByUsername("bob").orElseThrow().getId();
        Long channelId = createVoiceChannel(ownerToken, 1);
        mockMvc.perform(get("/api/channels/" + channelId + "/room").header("Authorization", "Bearer " + carolToken))
                .andExpect(status().isOk());
        // A custom role carrying the repurposed bit 14 — granted per-member, no ADMINISTRATOR.
        grantPermission(ownerToken, bobId, "RoomJumper", "JOIN_FULL_ROOMS");

        mockMvc.perform(get("/api/channels/" + channelId + "/room").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomId").value(channelId));
    }

    @Test
    void aTokenHolderReFetchesTheRoomFreely() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        String bobToken = registerAndLogin("bob", "supersecret");
        Long channelId = createVoiceChannel(ownerToken, 1);
        // bob passes the gate and mints his token, then sits in the tracker (voice.join).
        mockMvc.perform(get("/api/channels/" + channelId + "/room").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk());
        sitInRoom("bob-session", "bob", channelId);

        // The rejoin ladder re-fetches /room mid-talk — the live token is the pass-card…
        mockMvc.perform(get("/api/channels/" + channelId + "/room").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk());

        // …while the room stays genuinely full for everyone else.
        registerAndLogin("dave", "supersecret");
        mockMvc.perform(get("/api/channels/" + channelId + "/room")
                        .header("Authorization", "Bearer " + loginToken("dave", "supersecret")))
                .andExpect(status().isConflict());
    }

    @Test
    void loweringTheLimitBelowTheOccupancyEvictsNobody() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        String bobToken = registerAndLogin("bob", "supersecret");
        String carolToken = registerAndLogin("carol", "supersecret");
        Long channelId = createVoiceChannel(ownerToken, 2);
        // Two honest members under a limit of 2: each passed the gate and holds a token.
        mockMvc.perform(get("/api/channels/" + channelId + "/room").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/channels/" + channelId + "/room").header("Authorization", "Bearer " + carolToken))
                .andExpect(status().isOk());
        sitInRoom("bob-session", "bob", channelId);
        sitInRoom("carol-session", "carol", channelId);

        // Shrink the room under its live population: nobody is kicked…
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/channels/" + channelId + "/user-limit")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON).content("{\"userLimit\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userLimit").value(1));

        // …the seated member still re-enters freely (his token is the pass-card)…
        mockMvc.perform(get("/api/channels/" + channelId + "/room").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk());
        // …and the next outsider is refused.
        registerAndLogin("dave", "supersecret");
        mockMvc.perform(get("/api/channels/" + channelId + "/room")
                        .header("Authorization", "Bearer " + loginToken("dave", "supersecret")))
                .andExpect(status().isConflict());
    }

    private void sitInRoom(String sessionId, String username, Long channelId) {
        enteredSessions.add(sessionId);
        voiceMembership.enter(sessionId, username, channelId);
    }

    private void grantPermission(String ownerToken, Long userId, String roleName, String permission)
            throws Exception {
        MvcResult roleResult = mockMvc.perform(post("/api/roles").header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON)
                        .content(String.format("{\"name\":\"%s\",\"permissions\":[\"%s\"]}", roleName, permission)))
                .andExpect(status().isCreated())
                .andReturn();
        Long roleId = ((Number) JsonPath.read(roleResult.getResponse().getContentAsString(), "$.id")).longValue();
        mockMvc.perform(put("/api/members/" + userId + "/roles").header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON).content("{\"roleIds\":[" + roleId + "]}"))
                .andExpect(status().isOk());
    }

    private String loginToken(String username, String password) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(JSON)
                        .content(String.format("{\"username\":\"%s\",\"password\":\"%s\"}", username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(login.getResponse().getContentAsString(), "$.token");
    }

    private String registerAndLogin(String username, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(testInvites.registrationBody(username, password)))
                .andExpect(status().isCreated());
        return loginToken(username, password);
    }

    private Long createVoiceChannel(String token, Integer userLimit) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/channels")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(userLimit == null
                                ? "{\"name\":\"voice\",\"type\":\"VOICE\"}"
                                : String.format("{\"name\":\"voice\",\"type\":\"VOICE\",\"userLimit\":%d}", userLimit)))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }
}
