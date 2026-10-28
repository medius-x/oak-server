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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The backup block of the "Server" settings tab. The gate is deliberately STRICTER than the
 * rest of the tab (user decision 2026-10-28): OWNER or ADMINISTRATOR only — a narrow
 * MANAGE_SERVER holder must NOT reach the archive even though that bit opens other
 * /api/server endpoints. A manual pass builds a real zip (the noop object store contributes
 * zero objects; the seeder-committed rows — server_profile, roles — land in the dump), the
 * status poll latches the report, and the download streams the archive with the restore
 * script inside.
 */
@SpringBootTest(properties = "oak.backup.dir=target/oak-test-backup")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class BackupIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestInvites testInvites;

    @Autowired
    private oak.server.repository.UserRepository userRepository;

    @Test
    void endpointsAreGatedByAdministratorNotByManageServer() throws Exception {
        String ownerToken = registerAndLogin("backup-owner"); // first account = OWNER
        String memberToken = registerAndLogin("backup-plain");

        // A plain member gets 403 on every endpoint of the block.
        mockMvc.perform(get("/api/server/backup/status").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/server/backup/estimate").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/server/backup/run").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/server/backup/file").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden());

        // The crucial narrow case: MANAGE_SERVER alone is NOT enough for the archive.
        Long memberId = userRepository.findByUsername("backup-plain").orElseThrow().getId();
        Long roleId = createRole(ownerToken, "ProfileAdmin", "MANAGE_SERVER");
        assignRoles(ownerToken, memberId, roleId);
        mockMvc.perform(get("/api/server/backup/status").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/server/backup/run").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden());

        // No token at all — 401 before any gate.
        mockMvc.perform(get("/api/server/backup/status")).andExpect(status().isUnauthorized());

        // The owner passes.
        mockMvc.perform(get("/api/server/backup/status").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());
    }

    @Test
    void manualRunBuildsADownloadableArchive() throws Exception {
        String ownerToken = registerAndLogin("backup-runner");

        // Estimate: the noop object store holds nothing; the fields exist and are sane.
        mockMvc.perform(get("/api/server/backup/estimate").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objectBytes").value(0))
                .andExpect(jsonPath("$.objectCount").value(0));

        // 202 kicks the pass off; on the tiny H2 stand it may already have FINISHED by the time
        // the response is assembled — do not assert "running" here, poll the status below.
        mockMvc.perform(post("/api/server/backup/run").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isAccepted());

        // Poll until the background pass finishes (a plain loop — no awaitility dependency).
        boolean finished = false;
        String lastBody = "";
        for (int attempt = 0; attempt < 100 && !finished; attempt++) {
            Thread.sleep(100);
            MvcResult result = mockMvc.perform(get("/api/server/backup/status")
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isOk())
                    .andReturn();
            lastBody = result.getResponse().getContentAsString();
            finished = !Boolean.parseBoolean(JsonPath.read(lastBody, "$.running").toString())
                    && JsonPath.read(lastBody, "$.lastRun") != null
                    && JsonPath.read(lastBody, "$.lastRun.finishedAt") != null;
        }
        assertTrue(finished, "the backup run must finish and latch its report, got: " + lastBody);
        assertTrue(JsonPath.read(lastBody, "$.lastRun.error") == null,
                "the backup must not fail, got: " + JsonPath.read(lastBody, "$.lastRun.error"));
        assertTrue(((Number) JsonPath.read(lastBody, "$.lastRun.sizeBytes")).longValue() > 0);
        // The background thread runs OUTSIDE the test transaction, so it sees the
        // seeder-committed rows only: server_profile (1) + roles (@everyone, OWNER) = 3.
        assertTrue(((Number) JsonPath.read(lastBody, "$.lastRun.totalRows")).longValue() >= 3,
                "seeder tables must land in the dump, got: " + lastBody);

        // The download streams the zip; the restore script and the manifest are inside.
        MvcResult download = mockMvc.perform(get("/api/server/backup/file")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andReturn();
        byte[] zipBytes = download.getResponse().getContentAsByteArray();
        assertTrue(zipBytes.length > 4 && zipBytes[0] == 'P' && zipBytes[1] == 'K',
                "the download must be a zip archive");
        Map<String, byte[]> entries = unzip(zipBytes);
        assertTrue(entries.containsKey("manifest.json"), "entries: " + entries.keySet());
        assertTrue(entries.containsKey("data.sql.gz"), "entries: " + entries.keySet());
        String sql = gunzip(entries.get("data.sql.gz"));
        assertTrue(sql.contains("TRUNCATE TABLE server_profile"), "the restore script truncates");
        assertTrue(sql.contains("INSERT INTO server_profile"), "the seeder profile row is dumped");
        assertTrue(sql.contains("SET session_replication_role = replica;"));
    }

    private static Map<String, byte[]> unzip(byte[] archive) throws IOException {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                zip.transferTo(buffer);
                entries.put(entry.getName(), buffer.toByteArray());
            }
        }
        return entries;
    }

    private static String gunzip(byte[] compressed) throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Long createRole(String token, String name, String permission) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/roles").header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(String.format("{\"name\":\"%s\",\"permissions\":[\"%s\"]}", name, permission)))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private void assignRoles(String token, Long userId, Long roleId) throws Exception {
        mockMvc.perform(put("/api/members/" + userId + "/roles").header("Authorization", "Bearer " + token)
                        .contentType(JSON).content("{\"roleIds\":[" + roleId + "]}"))
                .andExpect(status().isOk());
    }

    private String registerAndLogin(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(testInvites.registrationBody(username, "supersecret123")))
                .andExpect(status().isCreated());
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType(JSON)
                        .content(String.format("{\"username\":\"%s\",\"password\":\"supersecret123\"}", username)))
                .andExpect(status().isOk())
                .andReturn();
        assertNotNull(result);
        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }
}
