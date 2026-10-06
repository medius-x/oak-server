package oak.server;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The server-logs download (logging round): MANAGE_STORAGE gates the endpoint like the rest of
 * the "Server" tab, and the answer is a real zip anchored by a manifest. The test profile points
 * the log directory at a throwaway folder and disables the docker tails — the developer machine
 * HAS a live ~/.oak-server and Docker, and the test must not read them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "oak.logging.directory=target/test-logs",
        "oak.logging.container-logs.enabled=false",
})
@Transactional
class LogsIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestInvites testInvites;

    @Test
    void endpointIsAuthenticated() throws Exception {
        mockMvc.perform(get("/api/server/logs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void endpointIsGatedByManageStorage() throws Exception {
        registerAndLogin("logs-owner"); // first account = OWNER — holds MANAGE_STORAGE implicitly
        String memberToken = registerAndLogin("logs-plain-member");
        mockMvc.perform(get("/api/server/logs").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void downloadAnswersAZipWithAManifestAndTheServerLog() throws Exception {
        Path directory = Path.of("target/test-logs");
        Files.createDirectories(directory);
        Path activeLog = directory.resolve("oak-server.log");
        Files.writeString(activeLog, "2026-10-06 test run line\n", StandardCharsets.UTF_8);

        String ownerToken = registerAndLogin("logs-archivist");
        MvcResult result = mockMvc.perform(get("/api/server/logs")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.startsWith("attachment; filename=\"server-logs-")))
                .andReturn();

        byte[] body = result.getResponse().getContentAsByteArray();
        assertEquals(0x50, body[0] & 0xFF, "the body starts with the zip magic 'P'");
        assertEquals(0x4B, body[1] & 0xFF, "the body starts with the zip magic 'K'");

        List<String> entryNames = new ArrayList<>();
        String manifest = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(body))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entryNames.add(entry.getName());
                if ("manifest.txt".equals(entry.getName())) {
                    manifest = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        assertTrue(entryNames.contains("manifest.txt"), "entries: " + entryNames);
        assertTrue(entryNames.contains("server/oak-server.log"), "entries: " + entryNames);
        assertNotNull(manifest);
        assertTrue(manifest.contains("generatedAt:"), manifest);
        assertTrue(manifest.contains("server/oak-server.log"), manifest);
    }

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
}
