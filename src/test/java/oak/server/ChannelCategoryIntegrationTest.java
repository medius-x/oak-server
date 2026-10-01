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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Category channels (the Discord-style sidebar sections) + the manual sidebar order:
 * creation with a parent, the parent validations, the non-empty deletion gate, the
 * reorder contract (full list, dense renumber, parent moves) and the (position, id)
 * serving order. Mirrors {@code PrivateChannelIntegrationTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ChannelCategoryIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestInvites testInvites;

    @Test
    void createsCategoryAndChannelsWithParentsInCreationOrder() throws Exception {
        String token = registerAndLogin("catalice", "supersecret");
        MvcResult category = mockMvc.perform(post("/api/channels")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON).content("{\"name\":\"General\",\"type\":\"CATEGORY\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("CATEGORY"))
                .andExpect(jsonPath("$.locked").value(false))
                .andExpect(jsonPath("$.parentId").doesNotExist())
                .andReturn();
        Long categoryId = idOf(category);

        // a TEXT channel grouped under the category carries the parentId and appends to the END
        mockMvc.perform(post("/api/channels")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(String.format("{\"name\":\"news\",\"type\":\"TEXT\",\"parentId\":%d}", categoryId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.parentId").value(categoryId));

        // the list serves (position, id): the category first, the child second
        mockMvc.perform(get("/api/channels").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("General"))
                .andExpect(jsonPath("$[1].name").value("news"));
    }

    @Test
    void rejectsInvalidParentsOnCreate() throws Exception {
        String token = registerAndLogin("catalice", "supersecret");
        Long voiceId = createChannel(token, "lobby", "VOICE");

        // a parent that does not exist
        mockMvc.perform(post("/api/channels")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON).content("{\"name\":\"x\",\"type\":\"TEXT\",\"parentId\":999999}"))
                .andExpect(status().isBadRequest());

        // a parent that is not a category
        mockMvc.perform(post("/api/channels")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(String.format("{\"name\":\"x\",\"type\":\"TEXT\",\"parentId\":%d}", voiceId)))
                .andExpect(status().isBadRequest());

        // a category carrying a parent
        mockMvc.perform(post("/api/channels")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(String.format("{\"name\":\"nested\",\"type\":\"CATEGORY\",\"parentId\":%d}", voiceId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refusesDeletingNonEmptyCategoryButRenamesIt() throws Exception {
        String token = registerAndLogin("catalice", "supersecret");
        Long categoryId = createChannel(token, "Games", "CATEGORY");
        createChannelInCategory(token, "lounge", categoryId);

        mockMvc.perform(delete("/api/channels/" + categoryId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Category is not empty — move or delete its channels first"));

        // rename works on a category like on any server channel
        mockMvc.perform(patch("/api/channels/" + categoryId + "/name")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON).content("{\"name\":\"Gaming\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Gaming"))
                .andExpect(jsonPath("$.locked").value(false));

        // once empty, deletion goes through
        Long childId = findChannelIdByName(token, "lounge");
        mockMvc.perform(delete("/api/channels/" + childId).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/channels/" + categoryId).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    @Test
    void reorderRewritesOrderAndParentsAtomically() throws Exception {
        String token = registerAndLogin("catalice", "supersecret");
        Long categoryId = createChannel(token, "Cat", "CATEGORY");
        Long newsId = createChannel(token, "news", "TEXT");
        Long randomId = createChannel(token, "random", "TEXT");

        // reorder requires MANAGE_CHANNELS — a plain second member gets 403
        String bobToken = registerAndLogin("catbob", "supersecret");
        mockMvc.perform(put("/api/channels/order").header("Authorization", "Bearer " + bobToken)
                        .contentType(JSON).content("{\"entries\":[]}"))
                .andExpect(status().isForbidden());

        // a partial list is rejected
        mockMvc.perform(put("/api/channels/order").header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(String.format("{\"entries\":[{\"id\":%d}]}", newsId)))
                .andExpect(status().isBadRequest());

        // a category as a child is rejected
        mockMvc.perform(put("/api/channels/order").header("Authorization", "Bearer " + token)
                        .contentType(JSON).content(body(List.of(
                                entry(categoryId, categoryId), entry(newsId, null), entry(randomId, null)))))
                .andExpect(status().isBadRequest());

        // the happy path: random first, then the category with news grouped under it
        mockMvc.perform(put("/api/channels/order").header("Authorization", "Bearer " + token)
                        .contentType(JSON).content(body(List.of(
                                entry(randomId, null), entry(categoryId, null), entry(newsId, categoryId)))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/channels").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("random"))
                .andExpect(jsonPath("$[1].name").value("Cat"))
                .andExpect(jsonPath("$[2].name").value("news"))
                .andExpect(jsonPath("$[2].parentId").value(categoryId))
                .andExpect(jsonPath("$[0].position").value(0))
                .andExpect(jsonPath("$[1].position").value(1))
                .andExpect(jsonPath("$[2].position").value(2));
    }

    private static String entry(Long id, Long parentId) {
        return parentId == null ? String.format("{\"id\":%d}", id)
                : String.format("{\"id\":%d,\"parentId\":%d}", id, parentId);
    }

    private static String body(List<String> entries) {
        return "{\"entries\":[" + String.join(",", entries) + "]}";
    }

    private static Long idOf(MvcResult result) throws Exception {
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private Long findChannelIdByName(String token, String name) throws Exception {
        MvcResult list = mockMvc.perform(get("/api/channels").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        List<?> names = JsonPath.read(list.getResponse().getContentAsString(), "$[*].name");
        for (int channelIndex = 0; channelIndex < names.size(); channelIndex++) {
            if (name.equals(names.get(channelIndex))) {
                return ((Number) JsonPath.read(list.getResponse().getContentAsString(),
                        "$[" + channelIndex + "].id")).longValue();
            }
        }
        throw new AssertionError("channel not found: " + name);
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

    private Long createChannel(String token, String name, String type) throws Exception {
        return idOf(mockMvc.perform(post("/api/channels")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(String.format("{\"name\":\"%s\",\"type\":\"%s\"}", name, type)))
                .andExpect(status().isCreated()).andReturn());
    }

    private Long createChannelInCategory(String token, String name, Long categoryId) throws Exception {
        return idOf(mockMvc.perform(post("/api/channels")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(String.format("{\"name\":\"%s\",\"type\":\"TEXT\",\"parentId\":%d}", name, categoryId)))
                .andExpect(status().isCreated()).andReturn());
    }
}
