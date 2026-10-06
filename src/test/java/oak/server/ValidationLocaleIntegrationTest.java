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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bean-validation messages must stay ENGLISH even for a Russian Accept-Language (live report
 * 2026-10-07: the stock Hibernate Validator messages were interpolated into Russian and leaked
 * into the error body, the [api] log and the client toast). FixedEnglishLocaleConfig pins the
 * request locale; this test guards the pin.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ValidationLocaleIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestInvites testInvites;

    @Test
    void validationMessagesStayEnglishUnderARussianAcceptLanguage() throws Exception {
        String ownerToken = registerAndLogin("locale-owner");
        MvcResult result = mockMvc.perform(patch("/api/users/me/username")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header("Accept-Language", "ru-RU,ru;q=0.9")
                        .contentType(JSON)
                        .content("{\"username\":\"ab\"}")) // @Size(min = 3, max = 32)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists())
                .andReturn();
        String message = JsonPath.read(result.getResponse().getContentAsString(), "$.message");
        assertTrue(message.contains("size must be between 3 and 32"),
                "the interpolated message must be the English stock text, got: " + message);
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
