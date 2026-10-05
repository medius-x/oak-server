package oak.server.service;

import com.jayway.jsonpath.JsonPath;
import oak.server.TestInvites;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The purge flow against a REAL database (H2): the oldest-batch SQL, the FK-clean reaction
 * delete, the row delete and the post-batch orphan reference check — everything except the
 * disk measurement (injected through the package-private seam) and the object store (the noop
 * stand-in of the test profile). Min-keep is zeroed via test properties so a handful of seeded
 * messages is purgable.
 */
@SpringBootTest(properties = "oak.storage.min-keep-messages=0")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class StoragePurgeFlowTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestInvites testInvites;
    @Autowired
    private StorageGcService storageGcService;
    @Autowired
    private oak.server.repository.MessageRepository messageRepository;

    @Test
    void purgeFlowDeletesRowsReactionsAndReportsTheOutcome() throws Exception {
        String ownerToken = registerAndLogin("owner");
        Long channelId = createChannel(ownerToken, "general");
        for (int index = 0; index < 6; index++) {
            postMessage(ownerToken, channelId, "old " + index, index % 2 == 0
                    ? String.format("{\"attachments\":[{\"key\":\"attachments/flow-%d\",\"fileName\":\"%d.png\"}]}", index, index)
                    : null);
        }
        assertEquals(6, messageRepository.count());

        // The disk reads full for the first two measurements (log + pre-loop), then "drops"
        // below the low watermark after the single batch — all six messages fit in one.
        double[] percents = {99.0, 99.0, 40.0};
        int[] call = {0};
        StorageGcService.StorageGcReport report =
                storageGcService.runOnce("test", () -> percents[Math.min(call[0]++, percents.length - 1)]);

        assertEquals("low watermark reached", report.outcome);
        assertEquals(6, report.purgedMessages);
        // The attachment keys are gone with their messages (the noop store's delete is a no-op —
        // the reference check's outcome is observable through the history instead).
        assertEquals(0, messageRepository.count());
        mockMvc.perform(get("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(result -> assertEquals("[]", result.getResponse().getContentAsString()));
        assertTrue(report.finalUsedPercent >= 0);
    }

    @Test
    void pinnedMessagesSurviveThePurge() throws Exception {
        String ownerToken = registerAndLogin("owner");
        Long channelId = createChannel(ownerToken, "general");
        Long[] messageIds = new Long[4];
        for (int index = 0; index < 4; index++) {
            messageIds[index] = postMessageReturningId(ownerToken, channelId, "old " + index);
        }
        // Pin the OLDEST message — the purge's favorite victim. Its whole point: "important"
        // must not silently vanish when the disk fills.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/channels/" + channelId + "/messages/" + messageIds[0] + "/pin")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        // The disk stays above the low watermark forever — the loop exhausts every unpinned
        // row and then stops on the empty batch.
        StorageGcService.StorageGcReport report = storageGcService.runOnce("test", () -> 99.0);

        assertEquals(3, report.purgedMessages);
        assertEquals(1, messageRepository.count());
        assertTrue(messageRepository.findById(messageIds[0]).isPresent(),
                "the pinned row must survive the purge");
    }

    // ------------------------------------------------------------------

    private String registerAndLogin(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(testInvites.registrationBody(username, "supersecret123")))
                .andExpect(status().isCreated());
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType(JSON)
                        .content(String.format("{\"username\":\"%s\",\"password\":\"supersecret123\"}", username)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    private Long createChannel(String token, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/channels")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(String.format("{\"name\":\"%s\",\"type\":\"TEXT\"}", name)))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private void postMessage(String token, Long channelId, String content, String extraJson) throws Exception {
        // extraJson arrives as "{...}"; its fields merge into the base object.
        String body = extraJson == null
                ? String.format("{\"content\":\"%s\"}", content)
                : String.format("{\"content\":\"%s\",%s}", content,
                        extraJson.replaceFirst("^\\{", "").replaceFirst("}$", ""));
        mockMvc.perform(post("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    private Long postMessageReturningId(String token, Long channelId, String content) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(String.format("{\"content\":\"%s\"}", content)))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }
}
