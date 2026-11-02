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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The server-to-server migration leg (2026-11-02): a one-shot token issued to the owner
 * lets the NEW server pull the built archive over the token-gated transfer endpoints —
 * with NO Bearer/JWT at all (permitAll path; the token IS the gate). The reachability
 * probe must not consume the token (the ranged curl -C - retries need it alive), and the
 * transferred bytes must match the digest the issue response carried.
 */
@SpringBootTest(properties = "oak.backup.dir=target/oak-test-backup-migration")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MigrationTransferIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestInvites testInvites;

    @Test
    void transferLegIssuesProbesAndDeliversOnTheTokenAlone() throws Exception {
        String ownerToken = registerAndLogin("migration-owner");
        String memberToken = registerAndLogin("migration-plain");

        // No archive yet — the issue honestly refuses.
        mockMvc.perform(post("/api/server/backup/migration-transfer")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isConflict());

        // Build the archive and wait it out.
        mockMvc.perform(post("/api/server/backup/run").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isAccepted());
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

        // The issue block is Bearer-gated like the rest: a plain member 403s, the owner
        // gets the raw token exactly once, with the digest and the size of THIS archive.
        mockMvc.perform(post("/api/server/backup/migration-transfer")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden());
        MvcResult issue = mockMvc.perform(post("/api/server/backup/migration-transfer")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.archiveSha256").isNotEmpty())
                .andExpect(jsonPath("$.archiveBytes").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andReturn();
        String migrationToken = (String) JsonPath.read(issue.getResponse().getContentAsString(), "$.token");
        String archiveSha256 = (String) JsonPath.read(issue.getResponse().getContentAsString(), "$.archiveSha256");
        long archiveBytes = ((Number) JsonPath.read(issue.getResponse().getContentAsString(), "$.archiveBytes")).longValue();

        // The probe: no Bearer header anywhere — the permitAll path's ONLY gate is the
        // token. Missing/forged → 401; valid → 204, and probing does NOT kill the token.
        mockMvc.perform(get("/api/server/backup/transfer/status"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/server/backup/transfer/status")
                        .header("X-Space-Migration-Token", "forged"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/server/backup/transfer/status")
                        .header("X-Space-Migration-Token", migrationToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/server/backup/transfer/status")
                        .header("X-Space-Migration-Token", migrationToken))
                .andExpect(status().isNoContent());

        // A ranged transfer-file request (the curl -C - resume shape): 206 with exactly
        // the requested bytes, on the token alone; a middle range does NOT deliver.
        MvcResult ranged = mockMvc.perform(get("/api/server/backup/transfer/file")
                        .header("X-Space-Migration-Token", migrationToken)
                        .header("Range", "bytes=0-3"))
                .andExpect(status().isPartialContent())
                .andReturn();
        assertEquals(4, ranged.getResponse().getContentAsByteArray().length);
        assertTrue(new java.io.File("target/oak-test-backup-migration/oak-backup.zip").isFile(),
                "a middle range must not delete the archive");
        // A forged token never reaches the file.
        mockMvc.perform(get("/api/server/backup/transfer/file")
                        .header("X-Space-Migration-Token", "forged"))
                .andExpect(status().isUnauthorized());

        // The full transfer: 200, the digest matches the issue response, the deliver-and-
        // delete rule fires, and the slot is free again afterwards (the stream closed).
        MvcResult delivered = mockMvc.perform(get("/api/server/backup/transfer/file")
                        .header("X-Space-Migration-Token", migrationToken))
                .andExpect(status().isOk())
                .andReturn();
        java.security.MessageDigest sha256 = java.security.MessageDigest.getInstance("SHA-256");
        String actual = java.util.HexFormat.of()
                .formatHex(sha256.digest(delivered.getResponse().getContentAsByteArray()));
        assertEquals(archiveSha256, actual, "the delivered bytes must match the issued digest");
        assertEquals(archiveBytes, delivered.getResponse().getContentAsByteArray().length);
        assertFalse(new java.io.File("target/oak-test-backup-migration/oak-backup.zip").isFile(),
                "the delivered archive must be deleted from the server disk");

        // The probe now honestly reports the empty slot (the archive is gone) — and a
        // REVOKE kills the token for good.
        mockMvc.perform(get("/api/server/backup/transfer/status")
                        .header("X-Space-Migration-Token", migrationToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/server/backup/migration-transfer")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/server/backup/transfer/file")
                        .header("X-Space-Migration-Token", migrationToken))
                .andExpect(status().isUnauthorized());
    }

    private String registerAndLogin(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(testInvites.registrationBody(username, "supersecret123")))
                .andExpect(status().isCreated());
        MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(JSON)
                        .content(String.format("{\"username\":\"%s\",\"password\":\"%s\"}", username, "supersecret123")))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(login.getResponse().getContentAsString(), "$.token");
    }
}
