package com.lockstep.fraud.web;

import com.lockstep.fraud.FraudIntegrationTest;
import com.lockstep.fraud.service.FraudCheckService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /flags: ADMIN only, problem+json errors, filtering by account. */
class FlagApiTest extends FraudIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FraudCheckService fraudCheckService;

    @Test
    void adminSeesFlagsForAnAccount() throws Exception {
        long account = newAccountId();
        Instant t = Instant.now().minus(Duration.ofDays(1));
        fraudCheckService.process(transfer(account, 100, t));
        fraudCheckService.process(transfer(account, 9_999, t.plus(Duration.ofHours(1))));

        mockMvc.perform(get("/flags").param("accountId", String.valueOf(account))
                        .header(HttpHeaders.AUTHORIZATION, bearer("admin", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].rule").value("LARGE_AMOUNT"))
                .andExpect(jsonPath("$[0].accountId").value(account))
                .andExpect(jsonPath("$[0].amountCents").value(9_999));
    }

    @Test
    void noTokenIs401ProblemJson() throws Exception {
        mockMvc.perform(get("/flags"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void customerIs403() throws Exception {
        mockMvc.perform(get("/flags").header(HttpHeaders.AUTHORIZATION, bearer("alice", "CUSTOMER")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void everyOtherEndpointIsClosed() throws Exception {
        mockMvc.perform(post("/flags").header(HttpHeaders.AUTHORIZATION, bearer("admin", "ADMIN")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/anything-else").header(HttpHeaders.AUTHORIZATION, bearer("admin", "ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void badLimitIs400ProblemJson() throws Exception {
        mockMvc.perform(get("/flags").param("limit", "0").header(HttpHeaders.AUTHORIZATION, bearer("admin", "ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }
}
