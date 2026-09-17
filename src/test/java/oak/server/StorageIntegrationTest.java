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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The "Server" storage settings: MANAGE_STORAGE gates every endpoint (the first-registered
 * owner holds it via ADMINISTRATOR; a plain member gets 403), the policy round-trips with
 * validation, and a manual run completes (noop object store, real test filesystem — well below
 * any watermark) with a latched report.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class StorageIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void endpointsAreGatedByManageStorage() throws Exception {
        registerAndLogin("the-owner"); // first account = OWNER — holds MANAGE_STORAGE implicitly
        String memberToken = registerAndLogin("plain-member");
        mockMvc.perform(get("/api/server/storage").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/server/storage").header("Authorization", "Bearer " + memberToken)
                        .contentType(JSON)
                        .content("{\"highWatermarkPercent\":80,\"lowWatermarkPercent\":40,\"gcMinuteOfDay\":300}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/server/storage/run").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void policyRoundTripsAndValidates() throws Exception {
        String ownerToken = registerAndLogin("owner");
        MvcResult first = mockMvc.perform(get("/api/server/storage")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policy.highWatermarkPercent").value(85))
                .andExpect(jsonPath("$.policy.gcMinuteOfDay").value(210))
                .andExpect(jsonPath("$.usage.usedPercent").exists())
                .andReturn();
        assertTrue(((Number) JsonPath.read(first.getResponse().getContentAsString(), "$.usage.totalBytes")).longValue() > 0,
                "the real filesystem is measured even in tests");

        mockMvc.perform(put("/api/server/storage").header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON)
                        .content("{\"highWatermarkPercent\":80,\"lowWatermarkPercent\":40,\"gcMinuteOfDay\":300,\"gcEnabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policy.highWatermarkPercent").value(80))
                .andExpect(jsonPath("$.policy.lowWatermarkPercent").value(40))
                .andExpect(jsonPath("$.policy.gcMinuteOfDay").value(300))
                .andExpect(jsonPath("$.policy.gcEnabled").value(true));

        // The automatic-cleanup switch round-trips too.
        mockMvc.perform(put("/api/server/storage").header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON)
                        .content("{\"highWatermarkPercent\":80,\"lowWatermarkPercent\":40,\"gcMinuteOfDay\":300,\"gcEnabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policy.gcEnabled").value(false));

        // low >= high is refused; so are out-of-range values (bean validation).
        mockMvc.perform(put("/api/server/storage").header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON)
                        .content("{\"highWatermarkPercent\":40,\"lowWatermarkPercent\":40,\"gcMinuteOfDay\":300}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/server/storage").header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON)
                        .content("{\"highWatermarkPercent\":120,\"lowWatermarkPercent\":40,\"gcMinuteOfDay\":300}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void manualRunCompletesAndLatchesTheReport() throws Exception {
        String ownerToken = registerAndLogin("runner");
        mockMvc.perform(post("/api/server/storage/run").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.outcome").value("started"));

        // Poll until the background pass finishes (a plain loop — no awaitility dependency).
        boolean finished = false;
        String lastBody = "";
        for (int attempt = 0; attempt < 100 && !finished; attempt++) {
            Thread.sleep(100);
            MvcResult result = mockMvc.perform(get("/api/server/storage")
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isOk())
                    .andReturn();
            lastBody = result.getResponse().getContentAsString();
            finished = !Boolean.parseBoolean(JsonPath.read(lastBody, "$.running").toString())
                    && JsonPath.read(lastBody, "$.lastRun") != null
                    && JsonPath.read(lastBody, "$.lastRun.finishedAt") != null;
        }
        assertTrue(finished, "the manual GC run must finish and latch its report, got: " + lastBody);
        assertTrue(((String) JsonPath.read(lastBody, "$.lastRun.trigger")).startsWith("manual"));
        assertTrue(!((String) JsonPath.read(lastBody, "$.lastRun.outcome")).isBlank());
    }

    private String registerAndLogin(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(String.format("{\"username\":\"%s\",\"password\":\"supersecret123\"}", username)))
                .andExpect(status().isCreated());
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType(JSON)
                        .content(String.format("{\"username\":\"%s\",\"password\":\"supersecret123\"}", username)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }
}
