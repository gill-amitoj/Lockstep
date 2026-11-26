package com.lockstep.ledger.security;

import com.jayway.jsonpath.JsonPath;
import com.lockstep.ledger.IntegrationTest;
import com.lockstep.ledger.domain.AuditEvent;
import com.lockstep.ledger.repository.AuditEventRepository;
import com.lockstep.ledger.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Registration and login over HTTP: the only endpoints that don't need a token. */
class AuthApiTest extends IntegrationTest {

    private static final String GOOD_PASSWORD = "correct-horse-battery";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private AuditEventRepository auditEvents;

    @Test
    void registerThenLoginThenUseTheToken() throws Exception {
        String username = newUsername();

        register(username, GOOD_PASSWORD)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        String body = login(username, GOOD_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.expires_in").value(3600))
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(body, "$.access_token");

        mockMvc.perform(post("/accounts").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerId").value(username));
    }

    @Test
    void passwordIsStoredAsBcryptHashNotPlainText() throws Exception {
        String username = newUsername();
        register(username, GOOD_PASSWORD).andExpect(status().isCreated());

        String hash = users.findByUsername(username).orElseThrow().getPasswordHash();
        assertThat(hash).isNotEqualTo(GOOD_PASSWORD).startsWith("$2a$");
    }

    @Test
    void duplicateUsernameReturns409() throws Exception {
        String username = newUsername();
        register(username, GOOD_PASSWORD).andExpect(status().isCreated());

        register(username, GOOD_PASSWORD)
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void weakPasswordOrBadUsernameReturns400() throws Exception {
        register(newUsername(), "short").andExpect(status().isBadRequest());
        register(newUsername(), "x".repeat(73)).andExpect(status().isBadRequest()); // BCrypt's 72-byte limit
        register("Has Spaces", GOOD_PASSWORD).andExpect(status().isBadRequest());
        register("#system", GOOD_PASSWORD).andExpect(status().isBadRequest());
        register("ab", GOOD_PASSWORD).andExpect(status().isBadRequest());
    }

    @Test
    void wrongPasswordAndUnknownUserGiveTheSame401() throws Exception {
        String username = newUsername();
        register(username, GOOD_PASSWORD).andExpect(status().isCreated());

        String wrongPassword = login(username, "not-the-password")
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn().getResponse().getContentAsString();
        String unknownUser = login(newUsername(), GOOD_PASSWORD)
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        // Same message either way: the response must not reveal whether the username exists.
        assertThat((String) JsonPath.read(wrongPassword, "$.detail"))
                .isEqualTo(JsonPath.read(unknownUser, "$.detail"));
    }

    @Test
    void failedAndSuccessfulLoginsAreAudited() throws Exception {
        String username = newUsername();
        register(username, GOOD_PASSWORD);

        login(username, "not-the-password").andExpect(status().isUnauthorized());
        login(username, GOOD_PASSWORD).andExpect(status().isOk());

        // LOGIN_FAILED survives even though the request ended in an exception (noRollbackFor).
        assertThat(auditEvents.findByActorOrderByIdAsc(username))
                .extracting(AuditEvent::getAction)
                .containsExactly("USER_REGISTERED", "LOGIN_FAILED", "LOGIN_SUCCEEDED");
    }

    @Test
    void registrationCannotCreateAnAdmin() throws Exception {
        String username = newUsername();

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "%s", "password": "%s", "role": "ADMIN"}
                                """.formatted(username, GOOD_PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("CUSTOMER"));

        mockMvc.perform(get("/admin/audit-events").header(HttpHeaders.AUTHORIZATION, bearer(username, Role.CUSTOMER)))
                .andExpect(status().isForbidden());
    }

    private ResultActions register(String username, String password) throws Exception {
        return mockMvc.perform(post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(credentials(username, password)));
    }

    private ResultActions login(String username, String password) throws Exception {
        return mockMvc.perform(post("/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(credentials(username, password)));
    }

    private static String credentials(String username, String password) {
        return """
                {"username": "%s", "password": "%s"}
                """.formatted(username, password);
    }

    private static String newUsername() {
        return "u-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
