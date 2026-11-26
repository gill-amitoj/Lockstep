package com.lockstep.ledger.reconciliation;

import com.lockstep.ledger.IntegrationTest;
import com.lockstep.ledger.domain.Account;
import com.lockstep.ledger.domain.Transfer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reconciliation must report a clean ledger as OK and catch every kind of corruption.
 *
 * To test detection we have to corrupt the ledger on purpose. That happens inside
 * a transaction that is ALWAYS rolled back (see corruptedThen), so the damage
 * never leaks into the database other tests share.
 */
class ReconciliationServiceTest extends IntegrationTest {

    @Autowired
    private ReconciliationService reconciliation;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void cleanLedgerReconcilesWithNoMismatches() {
        Account alice = openFundedAccount(5_000);
        Account bob = openFundedAccount(0);
        transferService.transfer(ownerOf(alice), alice.getId(), bob.getId(), 1_234L);

        ReconciliationReport report = reconciliation.run("test").orElseThrow();

        assertThat(report.run().status()).isEqualTo("OK");
        assertThat(report.mismatches()).isEmpty();
        assertThat(report.run().accountsChecked()).isGreaterThanOrEqualTo(3); // incl. SYSTEM
        assertThat(report.run().transfersChecked()).isGreaterThanOrEqualTo(2);
        // The run is recorded even when clean: proof that the check happened.
        assertThat(reconciliation.findRun(report.run().id())).isPresent();
    }

    @Test
    void detectsBalanceThatDoesNotMatchEntries() {
        Account alice = openFundedAccount(1_000);

        corruptedThen(jdbc -> jdbc.update("UPDATE accounts SET balance_cents = 1500 WHERE id = ?", alice.getId()),
                report -> {
                    assertThat(report.run().status()).isEqualTo("MISMATCH");
                    assertThat(report.mismatches()).anySatisfy(m -> {
                        assertThat(m.kind()).isEqualTo("ACCOUNT_BALANCE");
                        assertThat(m.accountId()).isEqualTo(alice.getId());
                        assertThat(m.expectedCents()).isEqualTo(1_000); // recomputed from entries
                        assertThat(m.actualCents()).isEqualTo(1_500);   // what the balance column claims
                    });
                    // Persisted, not just returned.
                    assertThat(reconciliation.findRun(report.run().id()).orElseThrow().mismatches())
                            .hasSameSizeAs(report.mismatches());
                });
    }

    @Test
    void detectsTransferWhoseEntriesDoNotSumToZero() {
        Account alice = openFundedAccount(1_000);
        Account bob = openFundedAccount(0);
        Transfer transfer = transferService.transfer(ownerOf(alice), alice.getId(), bob.getId(), 100L);

        // Entries are append-only, but INSERT is allowed: a stray third entry.
        corruptedThen(jdbc -> jdbc.update("""
                        INSERT INTO entries (transfer_id, account_id, amount_cents, created_at)
                        VALUES (?, ?, 50, now())
                        """, transfer.getId(), bob.getId()),
                report -> {
                    assertThat(report.mismatches()).anySatisfy(m -> {
                        assertThat(m.kind()).isEqualTo("TRANSFER_UNBALANCED");
                        assertThat(m.transferId()).isEqualTo(transfer.getId());
                        assertThat(m.actualCents()).isEqualTo(50);
                    });
                    // The same stray entry also breaks bob's balance and the global total.
                    assertThat(report.mismatches()).extracting(Mismatch::kind)
                            .contains("ACCOUNT_BALANCE", "LEDGER_NOT_ZERO");
                });
    }

    @Test
    void detectsEntriesOnTheWrongAccountEvenWhenTheySumToZero() {
        Account alice = openFundedAccount(1_000);
        Account bob = openFundedAccount(0);
        Account carol = openFundedAccount(0);
        Transfer transfer = transferService.transfer(ownerOf(alice), alice.getId(), bob.getId(), 100L);

        // +100 to carol and -100 back from bob: sums to zero, but the transfer said alice -> bob.
        corruptedThen(jdbc -> {
                    jdbc.update("INSERT INTO entries (transfer_id, account_id, amount_cents, created_at) VALUES (?, ?, 100, now())",
                            transfer.getId(), carol.getId());
                    jdbc.update("INSERT INTO entries (transfer_id, account_id, amount_cents, created_at) VALUES (?, ?, -100, now())",
                            transfer.getId(), bob.getId());
                },
                report -> assertThat(report.mismatches())
                        .anySatisfy(m -> assertThat(m.transferId()).isEqualTo(transfer.getId())));
    }

    /** With several app instances, only one run may happen at a time (PostgreSQL advisory lock). */
    @Test
    void secondConcurrentRunIsSkipped() throws Exception {
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (Statement statement = other.createStatement()) {
                // Another "instance" holds the reconciliation lock in its own open transaction.
                statement.execute("SELECT pg_advisory_xact_lock(" + ReconciliationService.ADVISORY_LOCK_KEY + ")");
            }

            assertThat(reconciliation.run("test")).isEmpty();

            other.rollback(); // releases the lock
        }
        assertThat(reconciliation.run("test")).isPresent();
    }

    @Test
    void reconciliationResultsCannotBeAltered() {
        long runId = reconciliation.run("test").orElseThrow().run().id();

        assertThatThrownBy(() -> jdbc.update("UPDATE reconciliation_runs SET status = 'OK' WHERE id = ?", runId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM reconciliation_runs WHERE id = ?", runId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void adminCanTriggerAndListRunsCustomerCannot() throws Exception {
        Account alice = openFundedAccount(0);

        mockMvc.perform(post("/admin/reconciliation-runs").header(HttpHeaders.AUTHORIZATION, adminBearer()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.run.status").value("OK"))
                .andExpect(jsonPath("$.mismatches.length()").value(0));

        mockMvc.perform(get("/admin/reconciliation-runs?limit=5").header(HttpHeaders.AUTHORIZATION, adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("OK"));

        mockMvc.perform(post("/admin/reconciliation-runs").header(HttpHeaders.AUTHORIZATION, bearerFor(alice)))
                .andExpect(status().isForbidden());
    }

    /**
     * Applies the corruption and runs reconciliation in ONE transaction, then
     * rolls it back unconditionally. ReconciliationService.run joins this
     * transaction (default propagation), so it sees the corrupted data.
     */
    private void corruptedThen(Consumer<JdbcTemplate> corruption, Consumer<ReconciliationReport> assertions) {
        transactionTemplate.executeWithoutResult(status -> {
            try {
                corruption.accept(jdbc);
                assertions.accept(reconciliation.run("test").orElseThrow());
            } finally {
                status.setRollbackOnly();
            }
        });
    }
}
