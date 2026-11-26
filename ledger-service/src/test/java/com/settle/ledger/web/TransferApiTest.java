package com.settle.ledger.web;

import com.settle.ledger.IntegrationTest;
import com.settle.ledger.domain.Account;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

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

        mockMvc.perform(post("/transfers")
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

        mockMvc.perform(post("/transfers")
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

        mockMvc.perform(post("/transfers")
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

        mockMvc.perform(post("/transfers")
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
        mockMvc.perform(post("/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    private static String transferJson(long from, long to, long amountCents) {
        return """
                {"fromAccountId": %d, "toAccountId": %d, "amountCents": %d}
                """.formatted(from, to, amountCents);
    }
}
