package com.lockstep.ledger.logging;

import com.lockstep.ledger.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class CorrelationIdTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void generatesAnIdWhenTheClientSendsNone() throws Exception {
        String id = mockMvc.perform(get("/accounts").header(HttpHeaders.AUTHORIZATION, adminBearer()))
                .andReturn().getResponse().getHeader(CorrelationIdFilter.HEADER);

        assertThat(UUID.fromString(id)).isNotNull(); // parses as a UUID
    }

    @Test
    void reusesAWellFormedIdFromTheClient() throws Exception {
        mockMvc.perform(get("/accounts")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer())
                        .header(CorrelationIdFilter.HEADER, "client-trace-42"))
                .andExpect(header().string(CorrelationIdFilter.HEADER, "client-trace-42"));
    }

    @Test
    void replacesAnUnsafeIdInsteadOfLoggingIt() throws Exception {
        String unsafe = "x".repeat(65); // too long; newlines and spaces are rejected the same way
        String returned = mockMvc.perform(get("/accounts")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer())
                        .header(CorrelationIdFilter.HEADER, unsafe))
                .andReturn().getResponse().getHeader(CorrelationIdFilter.HEADER);

        assertThat(returned).isNotEqualTo(unsafe);
        assertThat(UUID.fromString(returned)).isNotNull();
    }

    /** The id in the error body equals the response header, so support can find the log lines. */
    @Test
    void errorBodiesCarryTheSameIdAsTheHeader() throws Exception {
        mockMvc.perform(get("/accounts/{id}", Long.MAX_VALUE)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer())
                        .header(CorrelationIdFilter.HEADER, "trace-404"))
                .andExpect(header().string(CorrelationIdFilter.HEADER, "trace-404"))
                .andExpect(jsonPath("$.correlationId").value("trace-404"));

        // Also for 401, which is produced by Spring Security before any controller runs.
        mockMvc.perform(get("/accounts").header(CorrelationIdFilter.HEADER, "trace-401"))
                .andExpect(header().string(CorrelationIdFilter.HEADER, "trace-401"))
                .andExpect(jsonPath("$.correlationId").value("trace-401"));
    }

    @Test
    void mdcIsClearedAfterTheRequest() throws Exception {
        mockMvc.perform(get("/accounts").header(HttpHeaders.AUTHORIZATION, adminBearer()));

        // MockMvc runs the filter on this thread; a leak would hand the id to the next request.
        assertThat(org.slf4j.MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }
}
