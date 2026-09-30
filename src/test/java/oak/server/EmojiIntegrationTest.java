package oak.server;

import com.jayway.jsonpath.JsonPath;
import oak.server.entity.CustomEmoji;
import oak.server.entity.User;
import oak.server.repository.EmojiRepository;
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

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc coverage for the custom-emoji catalog on the H2 test profile (noop signer —
 * {@code url} is explicit null, asserted via JsonPath). The first registered user becomes
 * OWNER (holds MANAGE_EMOJIS through ADMINISTRATOR); a second plain user carries no rights.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class EmojiIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestInvites testInvites;

    @Autowired
    private EmojiRepository emojiRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void catalogRequiresAuth() throws Exception {
        mockMvc.perform(get("/api/emojis")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/emojis/slot").contentType(JSON).content("{\"fileName\":\"cat.png\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void mutationsRequireManageEmojis() throws Exception {
        String owner = registerAndLogin("owner-emoji", "supersecret");
        String plain = registerAndLogin("plain-emoji", "supersecret");
        String key = mintSlot(owner, "cat.png");

        mockMvc.perform(post("/api/emojis/slot").header("Authorization", "Bearer " + plain)
                        .contentType(JSON).content("{\"fileName\":\"cat.png\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/emojis").header("Authorization", "Bearer " + plain)
                        .contentType(JSON).content("{\"name\":\"cat\",\"fileKey\":\"" + key + "\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/emojis/1").header("Authorization", "Bearer " + plain))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/emojis").header("Authorization", "Bearer " + owner)
                        .contentType(JSON).content("{\"name\":\"cat\",\"fileKey\":\"" + key + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void slotMintsCustomEmojiKeyWithNullUrlUnderNoopSigner() throws Exception {
        String owner = registerAndLogin("owner-slot", "supersecret");
        MvcResult result = mockMvc.perform(post("/api/emojis/slot").header("Authorization", "Bearer " + owner)
                        .contentType(JSON).content("{\"fileName\":\"cat.gif\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertTrue(JsonPath.read(body, "$.key").toString().startsWith("custom-emojis/"),
                "key must live under the custom-emojis prefix: " + body);
        assertNull(JsonPath.read(body, "$.uploadUrl")); // noop signer — the URL field carries null
    }

    @Test
    void slotRejectsNonImageExtensions() throws Exception {
        String owner = registerAndLogin("owner-ext", "supersecret");
        mockMvc.perform(post("/api/emojis/slot").header("Authorization", "Bearer " + owner)
                        .contentType(JSON).content("{\"fileName\":\"cat.jpg\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/emojis/slot").header("Authorization", "Bearer " + owner)
                        .contentType(JSON).content("{\"fileName\":\"notes.txt\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createRoundTripsAndListIsNameOrdered() throws Exception {
        String owner = registerAndLogin("owner-create", "supersecret");
        Long ownerId = userRepository.findByUsername("owner-create").orElseThrow().getId();
        createEmoji(owner, "zebra", "zebra.png");
        createEmoji(owner, "cat", "cat.png");

        mockMvc.perform(get("/api/emojis").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("cat"))
                .andExpect(jsonPath("$[1].name").value("zebra"))
                .andExpect(jsonPath("$[0].uploadedBy").value(ownerId.intValue()));

        // noop signer: the catalog's url is explicit null (JsonPath — jsonPath().exists()
        // treats explicit null as absent).
        MvcResult listResult = mockMvc.perform(get("/api/emojis").header("Authorization", "Bearer " + owner))
                .andReturn();
        assertNull(JsonPath.read(listResult.getResponse().getContentAsString(), "$[0].url"));
    }

    @Test
    void createValidatesTheNameLadder() throws Exception {
        String owner = registerAndLogin("owner-ladder", "supersecret");
        String key = mintSlot(owner, "cat.png");
        mockMvc.perform(post("/api/emojis").header("Authorization", "Bearer " + owner)
                        .contentType(JSON).content("{\"name\":\"Cat\",\"fileKey\":\"" + key + "\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/emojis").header("Authorization", "Bearer " + owner)
                        .contentType(JSON).content("{\"name\":\"кот\",\"fileKey\":\"" + key + "\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/emojis").header("Authorization", "Bearer " + owner)
                        .contentType(JSON).content("{\"name\":\"a23456789012345678901234567890123\",\"fileKey\":\"" + key + "\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/emojis").header("Authorization", "Bearer " + owner)
                        .contentType(JSON).content("{\"name\":\"  \",\"fileKey\":\"" + key + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void duplicateNameConflicts() throws Exception {
        String owner = registerAndLogin("owner-dup", "supersecret");
        createEmoji(owner, "cat", "cat.png");
        String key = mintSlot(owner, "cat2.png");
        mockMvc.perform(post("/api/emojis").header("Authorization", "Bearer " + owner)
                        .contentType(JSON).content("{\"name\":\"cat\",\"fileKey\":\"" + key + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void foreignPrefixFileKeyIsRejected() throws Exception {
        String owner = registerAndLogin("owner-prefix", "supersecret");
        mockMvc.perform(post("/api/emojis").header("Authorization", "Bearer " + owner)
                        .contentType(JSON).content("{\"name\":\"cat\",\"fileKey\":\"attachments/abc/cat.png\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void catalogCapRejectsTheTwoHundredAndFirst() throws Exception {
        String owner = registerAndLogin("owner-cap", "supersecret");
        User uploader = userRepository.findByUsername("owner-cap").orElseThrow();
        for (int index = 0; index < 200; index++) {
            emojiRepository.save(new CustomEmoji("e" + index, "custom-emojis/u" + index + "/e.png", uploader));
        }
        String key = mintSlot(owner, "cat.png");
        mockMvc.perform(post("/api/emojis").header("Authorization", "Bearer " + owner)
                        .contentType(JSON).content("{\"name\":\"cat\",\"fileKey\":\"" + key + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteRemovesTheRowAndRepeatedDeleteIs404() throws Exception {
        String owner = registerAndLogin("owner-del", "supersecret");
        MvcResult created = mockMvc.perform(post("/api/emojis").header("Authorization", "Bearer " + owner)
                        .contentType(JSON).content("{\"name\":\"cat\",\"fileKey\":\"" + mintSlot(owner, "cat.png") + "\"}"))
                .andExpect(status().isOk()).andReturn();
        Long id = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();

        mockMvc.perform(delete("/api/emojis/" + id).header("Authorization", "Bearer " + owner))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/emojis/" + id).header("Authorization", "Bearer " + owner))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/emojis").header("Authorization", "Bearer " + owner))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void freshLinkServesEmojiKeysToo() throws Exception {
        String owner = registerAndLogin("owner-link", "supersecret");
        String key = mintSlot(owner, "cat.png");
        createEmoji(owner, "cat", "cat.png", key);

        mockMvc.perform(get("/api/attachments/link").param("key", key)
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/attachments/link").param("key", "custom-emojis/none/x.png")
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isNotFound());
    }

    private String registerAndLogin(String username, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(testInvites.registrationBody(username, password)))
                .andExpect(status().isCreated());
        MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(JSON)
                        .content(String.format("{\"username\":\"%s\",\"password\":\"%s\"}", username, password)))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(login.getResponse().getContentAsString(), "$.token");
    }

    private String mintSlot(String token, String fileName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/emojis/slot").header("Authorization", "Bearer " + token)
                        .contentType(JSON).content(String.format("{\"fileName\":\"%s\"}", fileName)))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.key");
    }

    private void createEmoji(String token, String name, String fileName) throws Exception {
        createEmoji(token, name, fileName, null);
    }

    private void createEmoji(String token, String name, String fileName, String preMintedKey) throws Exception {
        String key = preMintedKey != null ? preMintedKey : mintSlot(token, fileName);
        mockMvc.perform(post("/api/emojis").header("Authorization", "Bearer " + token)
                        .contentType(JSON).content(String.format("{\"name\":\"%s\",\"fileKey\":\"%s\"}", name, key)))
                .andExpect(status().isOk());
    }
}
