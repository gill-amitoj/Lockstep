package com.settle.ledger.web;

import com.jayway.jsonpath.JsonPath;
import com.settle.ledger.IntegrationTest;
import com.settle.ledger.domain.Account;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract: status codes, JSON shape, problem+json errors.
 * MockMvc calls the real controllers without opening a network port.
 */
class TransferApiTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void openAccountReturns201WithZeroBalance() throws Exception {
        mockMvc.perform(post("/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerId\": \"alice\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.ownerId").value("alice"))
                .andExpect(jsonPath("$.type").value("CUSTOMER"))
                .andExpect(jsonPath("$.currency").value("CAD"))
                .andExpect(jsonPath("$.balanceCents").value(0));
    }

    @Test
    void createTransferReturns201AndUpdatesBalances() throws Exception {
        Account alice = openFundedAccount(10_000);
        Account bob = openFundedAccount(0);

        mockMvc.perform(postTransfer()
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson(alice.getId(), bob.getId(), 2_500)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.fromAccountId").value(alice.getId()))
                .andExpect(jsonPath("$.toAccountId").value(bob.getId()))
                .andExpect(jsonPath("$.amountCents").value(2_500))
                .andExpect(jsonPath("$.currency").value("CAD"));

        mockMvc.perform(get("/accounts/{id}", bob.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceCents").value(2_500));

        mockMvc.perform(get("/accounts/{id}/entries", bob.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].amountCents").value(2_500));
    }

    @Test
    void insufficientFundsReturns422ProblemJson() throws Exception {
        Account alice = openFundedAccount(100);
        Account bob = openFundedAccount(0);

        mockMvc.perform(postTransfer()
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson(alice.getId(), bob.getId(), 101)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://settle.example/problems/insufficient-funds"))
                .andExpect(jsonPath("$.status").value(422));
    }

    @Test
    void sameAccountReturns400ProblemJson() throws Exception {
        Account alice = openFundedAccount(100);

        mockMvc.perform(postTransfer()
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson(alice.getId(), alice.getId(), 10)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://settle.example/problems/invalid-request"));
    }

    @Test
    void nonPositiveAmountReturns400() throws Exception {
        Account alice = openFundedAccount(100);
        Account bob = openFundedAccount(0);

        mockMvc.perform(postTransfer()
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson(alice.getId(), bob.getId(), 0)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownAccountReturns404ProblemJson() throws Exception {
        mockMvc.perform(get("/accounts/{id}", Long.MAX_VALUE))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void malformedJsonReturns400ProblemJson() throws Exception {
        mockMvc.perform(postTransfer()
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    /** Regression: Jackson used to truncate 10.99 to 10 and silently move the wrong amount. */
    @Test
    void fractionalAmountIsRejectedNotTruncated() throws Exception {
        Account alice = openFundedAccount(100);
        Account bob = openFundedAccount(0);

        mockMvc.perform(postTransfer()
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromAccountId": %d, "toAccountId": %d, "amountCents": 10.99}
                                """.formatted(alice.getId(), bob.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

        mockMvc.perform(get("/accounts/{id}", bob.getId()))
                .andExpect(jsonPath("$.balanceCents").value(0));
    }

    /** Regression: the POST response must match what the database stored (Postgres keeps microseconds). */
    @Test
    void createdAtInPostResponseMatchesStoredValue() throws Exception {
        Account alice = openFundedAccount(100);
        Account bob = openFundedAccount(0);

        String created = mockMvc.perform(postTransfer()
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson(alice.getId(), bob.getId(), 10)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Number id = JsonPath.read(created, "$.id");
        String createdAt = JsonPath.read(created, "$.createdAt");

        mockMvc.perform(get("/transfers/{id}", id.longValue()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdAt").value(createdAt));
    }

    /** POST /transfers with a fresh Idempotency-Key, so each call is a new request. */
    private static MockHttpServletRequestBuilder postTransfer() {
        return post("/transfers").header("Idempotency-Key", UUID.randomUUID().toString());
    }

    private static String transferJson(long from, long to, long amountCents) {
        return """
                {"fromAccountId": %d, "toAccountId": %d, "amountCents": %d}
                """.formatted(from, to, amountCents);
    }
}
