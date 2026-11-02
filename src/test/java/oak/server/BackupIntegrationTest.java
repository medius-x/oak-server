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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

        // Estimate: the noop object store holds nothing; the fields exist and are sane
        // (availableBytes ≥ 0 — the space check needs it).
        mockMvc.perform(get("/api/server/backup/estimate").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objectBytes").value(0))
                .andExpect(jsonPath("$.objectCount").value(0))
                .andExpect(jsonPath("$.availableBytes").isNotEmpty());

        // 202 kicks the pass off. The running-claim is taken in the CALLER's thread, so the
        // 202 body MUST say running=true even before the daemon thread does any work — the
        // regression of the live smoke (2026-10-28): a "not running, no last run" 202 made
        // the client give up instantly while the pass was still taking off.
        mockMvc.perform(post("/api/server/backup/run").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.running").value(true));

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

        // The FULL delivery deletes the server copy (the server keeps no built archive —
        // user decision 2026-10-28): the status reports the buffer gone, a second download
        // honestly 404s, and nothing is left on disk.
        mockMvc.perform(get("/api/server/backup/status").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileAvailable").value(false));
        mockMvc.perform(get("/api/server/backup/file").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
        assertFalse(new java.io.File("target/oak-test-backup/oak-backup.zip").isFile(),
                "the delivered archive must be deleted from the server disk");
    }

    @Test
    void rangedDeliveryServes206AndDeletesOnlyWhenTheTailIsDelivered() throws Exception {
        String ownerToken = registerAndLogin("backup-ranger");

        mockMvc.perform(post("/api/server/backup/run").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isAccepted());
        long size = 0;
        boolean finished = false;
        for (int attempt = 0; attempt < 100 && !finished; attempt++) {
            Thread.sleep(100);
            MvcResult result = mockMvc.perform(get("/api/server/backup/status")
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isOk())
                    .andReturn();
            String body = result.getResponse().getContentAsString();
            finished = !Boolean.parseBoolean(JsonPath.read(body, "$.running").toString())
                    && JsonPath.read(body, "$.lastRun") != null
                    && JsonPath.read(body, "$.lastRun.finishedAt") != null;
            if (finished) {
                size = ((Number) JsonPath.read(body, "$.lastRun.sizeBytes")).longValue();
            }
        }
        assertTrue(finished, "the backup run must finish");
        assertTrue(size > 4, "the archive must be bigger than the zip magic itself");

        // The first byte as a range: 206, the canonical Content-Range, exactly one byte,
        // and the transfer buffer SURVIVES (a middle range is not a delivery).
        MvcResult first = mockMvc.perform(get("/api/server/backup/file")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header("Range", "bytes=0-0"))
                .andExpect(status().isPartialContent())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Range", "bytes 0-0/" + size))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Accept-Ranges", "bytes"))
                .andReturn();
        byte[] firstByte = first.getResponse().getContentAsByteArray();
        assertEquals(1, firstByte.length, "bytes=0-0 must answer exactly one byte");
        assertEquals('P', firstByte[0], "the archive starts with the zip magic");

        // A middle slice: two bytes, still not the tail — the file stays.
        MvcResult middle = mockMvc.perform(get("/api/server/backup/file")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header("Range", "bytes=2-3"))
                .andExpect(status().isPartialContent())
                .andReturn();
        assertEquals(2, middle.getResponse().getContentAsByteArray().length,
                "bytes=2-3 must answer exactly two bytes");
        mockMvc.perform(get("/api/server/backup/status").header("Authorization", "Bearer " + ownerToken))
                .andExpect(jsonPath("$.fileAvailable").value(true));

        // The suffix form covers the tail — THIS delivery deletes the buffer.
        MvcResult tail = mockMvc.perform(get("/api/server/backup/file")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header("Range", "bytes=-4"))
                .andExpect(status().isPartialContent())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Range", "bytes " + (size - 4) + "-" + (size - 1) + "/" + size))
                .andReturn();
        assertEquals(4, tail.getResponse().getContentAsByteArray().length,
                "bytes=-4 must answer exactly four bytes");
        assertFalse(new java.io.File("target/oak-test-backup/oak-backup.zip").isFile(),
                "delivering the LAST byte must delete the server copy");
    }

    @Test
    void unusableRangeHeadersAnswer416OrFallBackToTheFullBody() throws Exception {
        String ownerToken = registerAndLogin("backup-ranger-edge");

        mockMvc.perform(post("/api/server/backup/run").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isAccepted());
        long size = 0;
        boolean finished = false;
        for (int attempt = 0; attempt < 100 && !finished; attempt++) {
            Thread.sleep(100);
            MvcResult result = mockMvc.perform(get("/api/server/backup/status")
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isOk())
                    .andReturn();
            String body = result.getResponse().getContentAsString();
            finished = !Boolean.parseBoolean(JsonPath.read(body, "$.running").toString())
                    && JsonPath.read(body, "$.lastRun") != null
                    && JsonPath.read(body, "$.lastRun.finishedAt") != null;
            if (finished) {
                size = ((Number) JsonPath.read(body, "$.lastRun.sizeBytes")).longValue();
            }
        }
        assertTrue(finished, "the backup run must finish");

        // Beyond the end: the canonical 416 with the total size — the answer curl -C -
        // reads as "nothing left to resume".
        mockMvc.perform(get("/api/server/backup/file")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header("Range", "bytes=" + (size + 100) + "-"))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Range", "bytes */" + size));
        // An empty suffix cannot be satisfied either.
        mockMvc.perform(get("/api/server/backup/file")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header("Range", "bytes=-0"))
                .andExpect(status().isRequestedRangeNotSatisfiable());

        // Multi-range is IGNORED per RFC 9110: a full 200 answers. The full fallback IS
        // a tail delivery — it deletes the server copy, so the garbage-header case needs
        // a fresh archive to prove its own full-200 fallback.
        MvcResult multi = mockMvc.perform(get("/api/server/backup/file")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header("Range", "bytes=0-1,5-9"))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(size, multi.getResponse().getContentAsByteArray().length,
                "a multi-range header must fall back to the full body");
        assertFalse(new java.io.File("target/oak-test-backup/oak-backup.zip").isFile(),
                "the full fallback delivery must delete the server copy");
        mockMvc.perform(get("/api/server/backup/file")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header("Range", "bytes=0-0"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/server/backup/run").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isAccepted());
        boolean rebuilt = false;
        for (int attempt = 0; attempt < 100 && !rebuilt; attempt++) {
            Thread.sleep(100);
            MvcResult result = mockMvc.perform(get("/api/server/backup/status")
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isOk())
                    .andReturn();
            String body = result.getResponse().getContentAsString();
            rebuilt = !Boolean.parseBoolean(JsonPath.read(body, "$.running").toString())
                    && JsonPath.read(body, "$.lastRun") != null
                    && JsonPath.read(body, "$.lastRun.finishedAt") != null;
            if (rebuilt) {
                size = ((Number) JsonPath.read(body, "$.lastRun.sizeBytes")).longValue();
            }
        }
        assertTrue(rebuilt, "the second backup run must finish");
        MvcResult garbage = mockMvc.perform(get("/api/server/backup/file")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header("Range", "bytes=abc-def"))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(size, garbage.getResponse().getContentAsByteArray().length,
                "a garbage header must fall back to the full body");
    }

    @Test
    void theReportCarriesTheArchiveSha256() throws Exception {
        String ownerToken = registerAndLogin("backup-hasher");

        mockMvc.perform(post("/api/server/backup/run").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isAccepted());
        String archiveSha256 = null;
        boolean finished = false;
        for (int attempt = 0; attempt < 100 && !finished; attempt++) {
            Thread.sleep(100);
            MvcResult result = mockMvc.perform(get("/api/server/backup/status")
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isOk())
                    .andReturn();
            String body = result.getResponse().getContentAsString();
            finished = !Boolean.parseBoolean(JsonPath.read(body, "$.running").toString())
                    && JsonPath.read(body, "$.lastRun") != null
                    && JsonPath.read(body, "$.lastRun.finishedAt") != null;
            if (finished) {
                archiveSha256 = (String) JsonPath.read(body, "$.lastRun.archiveSha256");
            }
        }
        assertTrue(finished, "the backup run must finish");
        assertNotNull(archiveSha256, "the report must carry the digest");
        assertTrue(archiveSha256.matches("[0-9a-f]{64}"),
                "a lowercase 64-hex SHA-256, got: " + archiveSha256);

        // The digest matches the delivered bytes — the migration leg relies on this
        // equality to verify a server-to-server transfer after it lands.
        MvcResult download = mockMvc.perform(get("/api/server/backup/file")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andReturn();
        java.security.MessageDigest sha256 = java.security.MessageDigest.getInstance("SHA-256");
        String actual = java.util.HexFormat.of().formatHex(sha256.digest(download.getResponse().getContentAsByteArray()));
        assertEquals(archiveSha256, actual, "the reported digest must match the delivered archive");
    }

    @Test
    void undeliveredArchiveCanBeDiscardedWithoutDownloading() throws Exception {
        String ownerToken = registerAndLogin("backup-discarder");

        mockMvc.perform(post("/api/server/backup/run").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.running").value(true));
        boolean finished = false;
        for (int attempt = 0; attempt < 100 && !finished; attempt++) {
            Thread.sleep(100);
            MvcResult result = mockMvc.perform(get("/api/server/backup/status")
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isOk())
                    .andReturn();
            String body = result.getResponse().getContentAsString();
            finished = !Boolean.parseBoolean(JsonPath.read(body, "$.running").toString())
                    && JsonPath.read(body, "$.lastRun") != null
                    && JsonPath.read(body, "$.lastRun.finishedAt") != null;
        }
        assertTrue(finished, "the backup run must finish");
        assertTrue(new java.io.File("target/oak-test-backup/oak-backup.zip").isFile(),
                "the built archive waits for download");
        mockMvc.perform(get("/api/server/backup/status").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileAvailable").value(true));

        // A plebeian must not discard the owner's archive; the owner's DELETE removes it
        // without a download, and the endpoint is idempotent.
        String memberToken = registerAndLogin("backup-discarder-pleb");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/server/backup/file").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/server/backup/file").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());
        assertFalse(new java.io.File("target/oak-test-backup/oak-backup.zip").isFile(),
                "the discarded archive must be gone");
        mockMvc.perform(get("/api/server/backup/status").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileAvailable").value(false));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/server/backup/file").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());
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
