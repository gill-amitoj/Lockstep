package com.lockstep.ledger.web;

import com.lockstep.ledger.IntegrationTest;
import com.lockstep.ledger.domain.Account;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The Idempotency-Key contract as a client sees it over HTTP. */
class IdempotencyApiTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void missingHeaderReturns400ProblemJsonAndMovesNoMoney() throws Exception {
        Account alice = openFundedAccount(1_000);
        Account bob = openFundedAccount(0);

        mockMvc.perform(post("/transfers")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson(alice.getId(), bob.getId(), 100)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Idempotency-Key header is required"));

        mockMvc.perform(get("/accounts/{id}", bob.getId()).header(HttpHeaders.AUTHORIZATION, adminBearer()))
                .andExpect(jsonPath("$.balanceCents").value(0));
    }

    @Test
    void retryWithSameKeyReturnsIdenticalResponse() throws Exception {
        Account alice = openFundedAccount(1_000);
        Account bob = openFundedAccount(0);
        String key = UUID.randomUUID().toString();
        String body = transferJson(alice.getId(), bob.getId(), 100);

        MockHttpServletResponse first = send(alice, key, body);
        MockHttpServletResponse retry = send(alice, key, body);

        assertThat(first.getStatus()).isEqualTo(201);
        assertThat(first.getHeader("Idempotent-Replayed")).isNull();

        assertThat(retry.getStatus()).isEqualTo(201);
        assertThat(retry.getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(retry.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(retry.getHeader("Location")).isEqualTo(first.getHeader("Location"));

        mockMvc.perform(get("/accounts/{id}", bob.getId()).header(HttpHeaders.AUTHORIZATION, adminBearer()))
                .andExpect(jsonPath("$.balanceCents").value(100));
    }

    @Test
    void sameKeyWithDifferentBodyReturns422ProblemJson() throws Exception {
        Account alice = openFundedAccount(1_000);
        Account bob = openFundedAccount(0);
        String key = UUID.randomUUID().toString();

        send(alice, key, transferJson(alice.getId(), bob.getId(), 100));

        mockMvc.perform(post("/transfers")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(alice))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson(alice.getId(), bob.getId(), 999)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://lockstep.example/problems/idempotency-key-reused"));

        mockMvc.perform(get("/accounts/{id}", bob.getId()).header(HttpHeaders.AUTHORIZATION, adminBearer()))
                .andExpect(jsonPath("$.balanceCents").value(100));
    }

    /** The hash is over the request's meaning, so whitespace and field order don't matter. */
    @Test
    void sameRequestFormattedDifferentlyCountsAsSameBody() throws Exception {
        Account alice = openFundedAccount(1_000);
        Account bob = openFundedAccount(0);
        String key = UUID.randomUUID().toString();

        send(alice, key, transferJson(alice.getId(), bob.getId(), 100));
        MockHttpServletResponse retry = send(alice, key, """
                {
                  "amountCents" : 100,
                  "toAccountId" : %d,
                  "fromAccountId" : %d
                }
                """.formatted(bob.getId(), alice.getId()));

        assertThat(retry.getStatus()).isEqualTo(201);
        assertThat(retry.getHeader("Idempotent-Replayed")).isEqualTo("true");
    }

    @Test
    void storedResponseHasSameJsonShapeAsGet() throws Exception {
        Account alice = openFundedAccount(1_000);
        Account bob = openFundedAccount(0);

        MockHttpServletResponse created = send(alice, UUID.randomUUID().toString(),
                transferJson(alice.getId(), bob.getId(), 100));

        mockMvc.perform(get(created.getHeader("Location")).header(HttpHeaders.AUTHORIZATION, bearerFor(alice)))
                .andExpect(status().isOk())
                .andExpect(content().json(created.getContentAsString(), JsonCompareMode.STRICT));
    }

    private MockHttpServletResponse send(Account sender, String key, String body) throws Exception {
        return mockMvc.perform(post("/transfers")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(sender))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(header().exists("Location"))
                .andReturn().getResponse();
    }

    private static String transferJson(long from, long to, long amountCents) {
        return """
                {"fromAccountId": %d, "toAccountId": %d, "amountCents": %d}
                """.formatted(from, to, amountCents);
    }
}
