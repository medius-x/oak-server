package oak.server;

import com.jayway.jsonpath.JsonPath;
import oak.server.entity.Sticker;
import oak.server.entity.StickerPack;
import oak.server.entity.User;
import oak.server.repository.StickerPackRepository;
import oak.server.repository.StickerRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc coverage for the custom-sticker catalog (packs + stickers + the message ride) on
 * the H2 test profile (noop signer — {@code url} is explicit null, asserted via JsonPath).
 * The first registered user becomes OWNER (holds MANAGE_EMOJIS through ADMINISTRATOR); a
 * second plain user carries no rights. Sticker management deliberately rides MANAGE_EMOJIS.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class StickerIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StickerPackRepository packRepository;

    @Autowired
    private StickerRepository stickerRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void catalogRequiresAuth() throws Exception {
        mockMvc.perform(get("/api/sticker-packs")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/stickers/slot").contentType(JSON).content("{\"fileName\":\"cat.png\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void mutationsRequireManageEmojis() throws Exception {
        String owner = registerAndLogin("owner-sticker", "supersecret");
        String plain = registerAndLogin("plain-sticker", "supersecret");
        Long packId = createPack(owner, "Cats");
        String key = mintSlot(owner, "cat.png");

        mockMvc.perform(post("/api/sticker-packs").header("Authorization", "Bearer " + plain)
                        .contentType(JSON).content("{\"name\":\"Dogs\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/stickers/slot").header("Authorization", "Bearer " + plain)
                        .contentType(JSON).content("{\"fileName\":\"cat.png\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/stickers").header("Authorization", "Bearer " + plain)
                        .contentType(JSON).content(stickerBody("cat", packId, key)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/stickers/1").header("Authorization", "Bearer " + plain))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/sticker-packs/" + packId).header("Authorization", "Bearer " + plain))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/stickers").header("Authorization", "Bearer " + owner)
                        .contentType(JSON).content(stickerBody("cat", packId, key)))
                .andExpect(status().isOk());
    }

    @Test
    void packCreateRoundTripsAndCatalogIsNameOrdered() throws Exception {
        String owner = registerAndLogin("owner-pack", "supersecret");
        Long ownerId = userRepository.findByUsername("owner-pack").orElseThrow().getId();
        Long catsId = createPack(owner, "Cats");
        Long dogsId = createPack(owner, "Dogs");
        createSticker(owner, "zebra", "zebra.png", catsId);
        createSticker(owner, "cat", "cat.png", catsId);
        createSticker(owner, "dog", "dog.png", dogsId);

        mockMvc.perform(get("/api/sticker-packs").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Cats"))
                .andExpect(jsonPath("$[1].name").value("Dogs"))
                .andExpect(jsonPath("$[0].stickers.length()").value(2))
                .andExpect(jsonPath("$[0].stickers[0].name").value("cat"))
                .andExpect(jsonPath("$[0].stickers[1].name").value("zebra"))
                .andExpect(jsonPath("$[1].stickers[0].name").value("dog"))
                .andExpect(jsonPath("$[0].stickers[0].uploadedBy").value(ownerId.intValue()));

        // noop signer: the sticker url is explicit null (JsonPath — jsonPath().exists()
        // treats explicit null as absent).
        MvcResult listResult = mockMvc.perform(get("/api/sticker-packs").header("Authorization", "Bearer " + owner))
                .andReturn();
        assertNull(JsonPath.read(listResult.getResponse().getContentAsString(), "$[0].stickers[0].url"));
    }

    @Test
    void duplicatePackNameConflicts() throws Exception {
        String owner = registerAndLogin("owner-packdup", "supersecret");
        createPack(owner, "Cats");
        mockMvc.perform(post("/api/sticker-packs").header("Authorization", "Bearer " + owner)
                        .contentType(JSON).content("{\"name\":\"Cats\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void packCapRejectsTheTwentyFirst() throws Exception {
        String owner = registerAndLogin("owner-packcap", "supersecret");
        for (int index = 0; index < 20; index++) {
            packRepository.save(new StickerPack("pack-" + index));
        }
        mockMvc.perform(post("/api/sticker-packs").header("Authorization", "Bearer " + owner)
                        .contentType(JSON).content("{\"name\":\"one-too-many\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void slotMintsStickerKeyWithNullUrlUnderNoopSigner() throws Exception {
        String owner = registerAndLogin("owner-sslots", "supersecret");
        MvcResult result = mockMvc.perform(post("/api/stickers/slot").header("Authorization", "Bearer " + owner)
                        .contentType(JSON).content("{\"fileName\":\"cat.gif\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertTrue(JsonPath.read(body, "$.key").toString().startsWith("stickers/"),
                "key must live under the stickers prefix: " + body);
        assertNull(JsonPath.read(body, "$.uploadUrl")); // noop signer — the URL field carries null
    }

    @Test
    void slotRejectsNonImageExtensions() throws Exception {
        String owner = registerAndLogin("owner-sext", "supersecret");
        mockMvc.perform(post("/api/stickers/slot").header("Authorization", "Bearer " + owner)
                        .contentType(JSON).content("{\"fileName\":\"cat.jpg\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/stickers/slot").header("Authorization", "Bearer " + owner)
                        .contentType(JSON).content("{\"fileName\":\"notes.txt\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void stickerCreateValidatesTheNameLadder() throws Exception {
        String owner = registerAndLogin("owner-sladder", "supersecret");
        Long packId = createPack(owner, "Cats");
        String key = mintSlot(owner, "cat.png");
        mockMvc.perform(post("/api/stickers").header("Authorization", "Bearer " + owner)
                        .contentType(JSON).content(stickerBody("Cat", packId, key)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/stickers").header("Authorization", "Bearer " + owner)
                        .contentType(JSON).content(stickerBody("кот", packId, key)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/stickers").header("Authorization", "Bearer " + owner)
                        .contentType(JSON).content(stickerBody("a23456789012345678901234567890123", packId, key)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/stickers").header("Authorization", "Bearer " + owner)
                        .contentType(JSON).content(stickerBody("  ", packId, key)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void stickerNameUniquenessIsPerPack() throws Exception {
        String owner = registerAndLogin("owner-spkg", "supersecret");
        Long catsId = createPack(owner, "Cats");
        Long dogsId = createPack(owner, "Dogs");
        createSticker(owner, "cat", "cat.png", catsId);
        String key = mintSlot(owner, "cat2.png");
        // Same name in the SAME pack conflicts…
        mockMvc.perform(post("/api/stickers").header("Authorization", "Bearer " + owner)
                        .contentType(JSON).content(stickerBody("cat", catsId, key)))
                .andExpect(status().isConflict());
        // …but a second pack may hold the same name.
        mockMvc.perform(post("/api/stickers").header("Authorization", "Bearer " + owner)
                        .contentType(JSON).content(stickerBody("cat", dogsId, key)))
                .andExpect(status().isOk());
    }

    @Test
    void stickerCapRejectsTheTwoHundredAndFirst() throws Exception {
        String owner = registerAndLogin("owner-scap", "supersecret");
        User uploader = userRepository.findByUsername("owner-scap").orElseThrow();
        StickerPack pack = packRepository.save(new StickerPack("Bulk"));
        for (int index = 0; index < 200; index++) {
            stickerRepository.save(new Sticker("s" + index, "stickers/u" + index + "/s.png", pack, uploader));
        }
        String key = mintSlot(owner, "cat.png");
        mockMvc.perform(post("/api/stickers").header("Authorization", "Bearer " + owner)
                        .contentType(JSON).content(stickerBody("cat", pack.getId(), key)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownPackAndForeignPrefixAreRejected() throws Exception {
        String owner = registerAndLogin("owner-s404", "supersecret");
        Long packId = createPack(owner, "Cats");
        String key = mintSlot(owner, "cat.png");
        mockMvc.perform(post("/api/stickers").header("Authorization", "Bearer " + owner)
                        .contentType(JSON).content(stickerBody("cat", 999L, key)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/stickers").header("Authorization", "Bearer " + owner)
                        .contentType(JSON).content(stickerBody("cat", packId, "attachments/abc/cat.png")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteStickerRemovesTheRowAndRepeatedDeleteIs404() throws Exception {
        String owner = registerAndLogin("owner-sdel", "supersecret");
        Long packId = createPack(owner, "Cats");
        Long stickerId = createSticker(owner, "cat", "cat.png", packId);

        mockMvc.perform(delete("/api/stickers/" + stickerId).header("Authorization", "Bearer " + owner))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/stickers/" + stickerId).header("Authorization", "Bearer " + owner))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/sticker-packs").header("Authorization", "Bearer " + owner))
                .andExpect(jsonPath("$[0].stickers.length()").value(0));
    }

    @Test
    void deletePackRemovesItsStickers() throws Exception {
        String owner = registerAndLogin("owner-pdel", "supersecret");
        Long catsId = createPack(owner, "Cats");
        Long dogsId = createPack(owner, "Dogs");
        createSticker(owner, "cat", "cat.png", catsId);
        createSticker(owner, "dog", "dog.png", dogsId);

        mockMvc.perform(delete("/api/sticker-packs/" + catsId).header("Authorization", "Bearer " + owner))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/sticker-packs/" + catsId).header("Authorization", "Bearer " + owner))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/sticker-packs").header("Authorization", "Bearer " + owner))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Dogs"))
                .andExpect(jsonPath("$[0].stickers.length()").value(1));
    }

    @Test
    void messageSendCarriesTheStickerDenormalized() throws Exception {
        String owner = registerAndLogin("owner-send", "supersecret");
        Long channelId = createChannel(owner, "chat", "TEXT");
        Long packId = createPack(owner, "Cats");
        Long stickerId = createSticker(owner, "cat", "cat.png", packId);

        MvcResult sent = mockMvc.perform(post("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(JSON)
                        .content("{\"content\":null,\"stickerId\":" + stickerId + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sticker.name").value("cat"))
                .andExpect(jsonPath("$.sticker.key").exists())
                .andReturn();
        assertNull(JsonPath.read(sent.getResponse().getContentAsString(), "$.sticker.url"));
        Long messageId = ((Number) JsonPath.read(sent.getResponse().getContentAsString(), "$.id")).longValue();

        // The history fetch carries the same denormalized copy.
        mockMvc.perform(get("/api/channels/" + channelId + "/messages").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(messageId.intValue()))
                .andExpect(jsonPath("$[0].sticker.name").value("cat"));
    }

    @Test
    void unknownStickerIdIsRejected() throws Exception {
        String owner = registerAndLogin("owner-unknown", "supersecret");
        Long channelId = createChannel(owner, "chat", "TEXT");
        mockMvc.perform(post("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(JSON)
                        .content("{\"content\":null,\"stickerId\":999}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void stickerOnlyMessageAllowsBlankContentEdit() throws Exception {
        String owner = registerAndLogin("owner-sedit", "supersecret");
        Long channelId = createChannel(owner, "chat", "TEXT");
        Long packId = createPack(owner, "Cats");
        Long stickerId = createSticker(owner, "cat", "cat.png", packId);
        Long messageId = sendStickerMessage(owner, channelId, stickerId);

        mockMvc.perform(patch("/api/channels/" + channelId + "/messages/" + messageId)
                        .header("Authorization", "Bearer " + owner)
                        .contentType(JSON)
                        .content("{\"content\":\"  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sticker.name").value("cat"));
    }

    @Test
    void intraInstanceForwardCopiesTheSticker() throws Exception {
        String owner = registerAndLogin("owner-sfwd", "supersecret");
        Long channelId = createChannel(owner, "chat", "TEXT");
        Long targetId = createChannel(owner, "target", "TEXT");
        Long packId = createPack(owner, "Cats");
        Long stickerId = createSticker(owner, "cat", "cat.png", packId);
        Long messageId = sendStickerMessage(owner, channelId, stickerId);

        mockMvc.perform(post("/api/channels/" + targetId + "/messages/forward")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(JSON)
                        .content("{\"sourceMessageId\":" + messageId + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sticker.name").value("cat"))
                .andExpect(jsonPath("$.forward.trusted").value(true));
    }

    @Test
    void freshLinkServesStickerKeysToo() throws Exception {
        String owner = registerAndLogin("owner-slink", "supersecret");
        Long packId = createPack(owner, "Cats");
        String key = mintSlot(owner, "cat.png");
        createSticker(owner, "cat", "cat.png", packId, key);

        mockMvc.perform(get("/api/attachments/link").param("key", key)
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/attachments/link").param("key", "stickers/none/x.png")
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isNotFound());
    }

    @Test
    void stickerOnlyReplyQuoteShowsTheStickerLabel() throws Exception {
        String owner = registerAndLogin("owner-sreply", "supersecret");
        Long channelId = createChannel(owner, "chat", "TEXT");
        Long packId = createPack(owner, "Cats");
        Long stickerId = createSticker(owner, "cat", "cat.png", packId);
        Long messageId = sendStickerMessage(owner, channelId, stickerId);

        mockMvc.perform(post("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(JSON)
                        .content("{\"content\":\"nice one\",\"replyToId\":" + messageId + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reply.preview").value("[Sticker] cat"));
    }

    @Test
    void stickerOnlyRowSurvivesAuthorOnlySearch() throws Exception {
        String owner = registerAndLogin("owner-ssearch", "supersecret");
        Long channelId = createChannel(owner, "chat", "TEXT");
        Long packId = createPack(owner, "Cats");
        Long stickerId = createSticker(owner, "cat", "cat.png", packId);
        sendStickerMessage(owner, channelId, stickerId);

        mockMvc.perform(get("/api/search/messages").param("author", "owner-ssearch")
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.results[0].sticker.name").value("cat"));
    }

    private String registerAndLogin(String username, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(String.format("{\"username\":\"%s\",\"password\":\"%s\"}", username, password)))
                .andExpect(status().isCreated());
        MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(JSON)
                        .content(String.format("{\"username\":\"%s\",\"password\":\"%s\"}", username, password)))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(login.getResponse().getContentAsString(), "$.token");
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

    private Long createPack(String token, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/sticker-packs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(String.format("{\"name\":\"%s\"}", name)))
                .andExpect(status().isOk())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private String mintSlot(String token, String fileName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/stickers/slot").header("Authorization", "Bearer " + token)
                        .contentType(JSON).content(String.format("{\"fileName\":\"%s\"}", fileName)))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.key");
    }

    private Long createSticker(String token, String name, String fileName, Long packId) throws Exception {
        return createSticker(token, name, fileName, packId, null);
    }

    private Long createSticker(String token, String name, String fileName, Long packId, String preMintedKey)
            throws Exception {
        String key = preMintedKey != null ? preMintedKey : mintSlot(token, fileName);
        MvcResult result = mockMvc.perform(post("/api/stickers").header("Authorization", "Bearer " + token)
                        .contentType(JSON).content(stickerBody(name, packId, key)))
                .andExpect(status().isOk())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private static String stickerBody(String name, Long packId, String fileKey) {
        return String.format("{\"name\":\"%s\",\"packId\":%d,\"fileKey\":\"%s\"}", name, packId, fileKey);
    }

    private Long sendStickerMessage(String token, Long channelId, Long stickerId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content("{\"content\":null,\"stickerId\":" + stickerId + "}"))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }
}
