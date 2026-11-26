package com.lockstep.ledger.audit;

import com.jayway.jsonpath.JsonPath;
import com.lockstep.ledger.IntegrationTest;
import com.lockstep.ledger.domain.Account;
import com.lockstep.ledger.domain.AuditEvent;
import com.lockstep.ledger.repository.AuditEventRepository;
import com.lockstep.ledger.service.InsufficientFundsException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuditLogTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuditEventRepository auditEvents;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void transferIsAuditedWithActorDetailsAndCorrelationId() throws Exception {
        Account alice = openFundedAccount(1_000);
        Account bob = openFundedAccount(0);
        String correlationId = "audit-test-" + UUID.randomUUID().toString().substring(0, 8);

        String response = mockMvc.perform(post("/transfers")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(alice))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-Correlation-Id", correlationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromAccountId": %d, "toAccountId": %d, "amountCents": 250}
                                """.formatted(alice.getId(), bob.getId())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Number transferId = JsonPath.read(response, "$.id");

        List<AuditEvent> events = auditEvents.findByResourceTypeAndResourceIdOrderByIdAsc(
                "transfer", transferId.toString());
        assertThat(events).hasSize(1);
        AuditEvent event = events.getFirst();
        assertThat(event.getActor()).isEqualTo(alice.getOwnerId());
        assertThat(event.getAction()).isEqualTo("TRANSFER_CREATED");
        assertThat(event.getCorrelationId()).isEqualTo(correlationId);
        assertThat(event.getOccurredAt()).isNotNull();
        assertThat(event.getDetails())
                .contains("\"amountCents\":250")
                .contains("\"fromAccountId\":" + alice.getId());
    }

    @Test
    void accountOpeningIsAudited() {
        Account alice = openFundedAccount(0);

        assertThat(auditEvents.findByResourceTypeAndResourceIdOrderByIdAsc("account", alice.getId().toString()))
                .extracting(AuditEvent::getAction, AuditEvent::getActor)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("ACCOUNT_OPENED", alice.getOwnerId()));
    }

    /** The audit row is part of the transfer's transaction: a rejected transfer leaves no audit row either. */
    @Test
    void rolledBackTransferLeavesNoAuditRecord() {
        Account alice = openFundedAccount(100);
        Account bob = openFundedAccount(0);
        long before = auditEvents.count();

        assertThatThrownBy(() -> transferService.transfer(ownerOf(alice), alice.getId(), bob.getId(), 999L))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(auditEvents.count()).isEqualTo(before);
    }

    @Test
    void auditRowsCannotBeUpdatedDeletedOrTruncated() {
        openFundedAccount(0); // guarantees at least one audit row exists

        assertThatThrownBy(() -> jdbc.update("UPDATE audit_log SET actor = 'someone-else'"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_log"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE audit_log"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThat(auditEvents.count()).isPositive();
    }

    @Test
    void ledgerTablesCannotBeTruncated() {
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE entries"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE transfers CASCADE"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void adminCanReadTheAuditLogNewestFirst() throws Exception {
        Account alice = openFundedAccount(0);

        mockMvc.perform(get("/admin/audit-events?limit=1").header(HttpHeaders.AUTHORIZATION, adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].action").value("ACCOUNT_OPENED"))
                .andExpect(jsonPath("$[0].resourceId").value(alice.getId().toString()));

        mockMvc.perform(get("/admin/audit-events?limit=0").header(HttpHeaders.AUTHORIZATION, adminBearer()))
                .andExpect(status().isBadRequest());
    }
}
