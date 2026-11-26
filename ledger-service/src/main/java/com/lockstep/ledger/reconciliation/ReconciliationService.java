package com.lockstep.ledger.reconciliation;

import com.lockstep.ledger.domain.Timestamps;
import com.lockstep.ledger.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Independently re-checks the ledger from its raw entries:
 *
 *  1. ACCOUNT_BALANCE:     accounts.balance_cents == SUM(entries) for every account
 *  2. TRANSFER_UNBALANCED: every transfer has exactly one debit of -amount on its
 *                          "from" account and one credit of +amount on its "to"
 *                          account (so its entries sum to zero)
 *  3. LEDGER_NOT_ZERO:     the sum of ALL entries is zero (money is never created)
 *
 * Every run is recorded, with or without findings. Nothing is "fixed"
 * automatically: a mismatch means something is badly wrong, and a person must
 * investigate. Silently correcting it would destroy the evidence.
 *
 * Plain SQL (JdbcClient) instead of JPA, because these are set-based aggregate
 * checks over whole tables; that is what SQL is for. JdbcClient joins the same
 * transaction as JPA.
 */
@Service
public class ReconciliationService {

    /** Arbitrary constant naming "the reconciliation job" for pg_try_advisory_xact_lock. */
    static final long ADVISORY_LOCK_KEY = 7_340_001L;

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final JdbcClient jdbc;
    private final AuditService audit;

    public ReconciliationService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /**
     * REPEATABLE_READ: every query below sees the same snapshot of the database,
     * as of the first query. Transfers committing during the run can't make the
     * account check and the transfer check disagree with each other. (Each
     * transfer updates balances and entries in one transaction, so any snapshot
     * is internally consistent.) Reads never block writers in PostgreSQL, so
     * the job does not slow transfers down.
     *
     * @return the report, or empty if another run is already in progress
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Optional<ReconciliationReport> run(String actor) {
        // Only one run at a time, even with several app instances: a PostgreSQL
        // advisory lock, held until this transaction ends. "try" = don't wait.
        Boolean acquired = jdbc.sql("SELECT pg_try_advisory_xact_lock(:key)")
                .param("key", ADVISORY_LOCK_KEY)
                .query(Boolean.class)
                .single();
        if (!acquired) {
            log.info("Reconciliation skipped: another run is in progress");
            return Optional.empty();
        }

        Instant startedAt = Timestamps.now();
        long accountsChecked = jdbc.sql("SELECT count(*) FROM accounts").query(Long.class).single();
        long transfersChecked = jdbc.sql("SELECT count(*) FROM transfers").query(Long.class).single();

        List<Mismatch> mismatches = new ArrayList<>();
        mismatches.addAll(findAccountBalanceMismatches());
        mismatches.addAll(findUnbalancedTransfers());
        findLedgerTotalMismatch().ifPresent(mismatches::add);

        Instant finishedAt = Timestamps.now();
        String status = mismatches.isEmpty() ? "OK" : "MISMATCH";
        long runId = insertRun(startedAt, finishedAt, accountsChecked, transfersChecked, mismatches.size(), status);
        for (Mismatch mismatch : mismatches) {
            insertMismatch(runId, mismatch);
            log.atError()
                    .addKeyValue("reconciliationRunId", runId)
                    .addKeyValue("kind", mismatch.kind())
                    .addKeyValue("accountId", mismatch.accountId())
                    .addKeyValue("transferId", mismatch.transferId())
                    .log("Reconciliation mismatch: {}", mismatch.detail());
        }

        audit.record(actor, "RECONCILIATION_RUN", "reconciliation_run", runId,
                Map.of("status", status, "mismatchCount", mismatches.size()));
        log.atInfo()
                .addKeyValue("reconciliationRunId", runId)
                .addKeyValue("status", status)
                .addKeyValue("accountsChecked", accountsChecked)
                .addKeyValue("transfersChecked", transfersChecked)
                .addKeyValue("mismatchCount", mismatches.size())
                .log("Reconciliation finished");

        ReconciliationRun run = new ReconciliationRun(runId, startedAt, finishedAt,
                accountsChecked, transfersChecked, mismatches.size(), status);
        return Optional.of(new ReconciliationReport(run, mismatches));
    }

    @Transactional(readOnly = true)
    public List<ReconciliationRun> recentRuns(int limit) {
        return jdbc.sql("SELECT * FROM reconciliation_runs ORDER BY id DESC LIMIT :limit")
                .param("limit", limit)
                .query((rs, rowNum) -> mapRun(rs))
                .list();
    }

    @Transactional(readOnly = true)
    public Optional<ReconciliationReport> findRun(long runId) {
        return jdbc.sql("SELECT * FROM reconciliation_runs WHERE id = :id")
                .param("id", runId)
                .query((rs, rowNum) -> mapRun(rs))
                .optional()
                .map(run -> new ReconciliationReport(run, mismatchesOf(runId)));
    }

    // ---- the three checks ------------------------------------------------

    private List<Mismatch> findAccountBalanceMismatches() {
        // LEFT JOIN so an account with a non-zero balance but NO entries is caught too.
        return jdbc.sql("""
                SELECT a.id, a.balance_cents, COALESCE(SUM(e.amount_cents), 0) AS entries_sum
                  FROM accounts a
                  LEFT JOIN entries e ON e.account_id = a.id
                 GROUP BY a.id
                HAVING a.balance_cents <> COALESCE(SUM(e.amount_cents), 0)
                 ORDER BY a.id
                """)
                .query((rs, rowNum) -> new Mismatch(
                        "ACCOUNT_BALANCE",
                        rs.getLong("id"),
                        null,
                        rs.getLong("entries_sum"),
                        rs.getLong("balance_cents"),
                        "Stored balance " + rs.getLong("balance_cents")
                                + " does not equal sum of entries " + rs.getLong("entries_sum")))
                .list();
    }

    private List<Mismatch> findUnbalancedTransfers() {
        // FILTER counts only the rows matching its condition. A correct transfer has
        // exactly 2 entries: one exact debit on "from" and one exact credit on "to".
        return jdbc.sql("""
                SELECT t.id, t.amount_cents,
                       COUNT(e.id)                        AS entry_count,
                       COALESCE(SUM(e.amount_cents), 0)   AS entries_sum
                  FROM transfers t
                  LEFT JOIN entries e ON e.transfer_id = t.id
                 GROUP BY t.id
                HAVING COUNT(e.id) <> 2
                    OR COALESCE(SUM(e.amount_cents), 0) <> 0
                    OR COUNT(*) FILTER (WHERE e.account_id = t.from_account_id
                                          AND e.amount_cents = -t.amount_cents) <> 1
                    OR COUNT(*) FILTER (WHERE e.account_id = t.to_account_id
                                          AND e.amount_cents = t.amount_cents) <> 1
                 ORDER BY t.id
                """)
                .query((rs, rowNum) -> new Mismatch(
                        "TRANSFER_UNBALANCED",
                        null,
                        rs.getLong("id"),
                        0L,
                        rs.getLong("entries_sum"),
                        "Transfer of " + rs.getLong("amount_cents") + " has " + rs.getLong("entry_count")
                                + " entries summing to " + rs.getLong("entries_sum")))
                .list();
    }

    private Optional<Mismatch> findLedgerTotalMismatch() {
        long total = jdbc.sql("SELECT COALESCE(SUM(amount_cents), 0) FROM entries").query(Long.class).single();
        if (total == 0) {
            return Optional.empty();
        }
        return Optional.of(new Mismatch("LEDGER_NOT_ZERO", null, null, 0L, total,
                "Sum of all entries is " + total + ", expected 0"));
    }

    // ---- persistence ------------------------------------------------------

    private long insertRun(Instant startedAt, Instant finishedAt, long accountsChecked,
                           long transfersChecked, int mismatchCount, String status) {
        return jdbc.sql("""
                INSERT INTO reconciliation_runs
                    (started_at, finished_at, accounts_checked, transfers_checked, mismatch_count, status)
                VALUES (:startedAt, :finishedAt, :accountsChecked, :transfersChecked, :mismatchCount, :status)
                RETURNING id
                """)
                .param("startedAt", utc(startedAt))
                .param("finishedAt", utc(finishedAt))
                .param("accountsChecked", accountsChecked)
                .param("transfersChecked", transfersChecked)
                .param("mismatchCount", mismatchCount)
                .param("status", status)
                .query(Long.class)
                .single();
    }

    private void insertMismatch(long runId, Mismatch mismatch) {
        jdbc.sql("""
                INSERT INTO reconciliation_mismatches
                    (run_id, kind, account_id, transfer_id, expected_cents, actual_cents, detail)
                VALUES (:runId, :kind, :accountId, :transferId, :expected, :actual, :detail)
                """)
                .param("runId", runId)
                .param("kind", mismatch.kind())
                .param("accountId", mismatch.accountId())
                .param("transferId", mismatch.transferId())
                .param("expected", mismatch.expectedCents())
                .param("actual", mismatch.actualCents())
                .param("detail", mismatch.detail())
                .update();
    }

    private List<Mismatch> mismatchesOf(long runId) {
        return jdbc.sql("SELECT * FROM reconciliation_mismatches WHERE run_id = :runId ORDER BY id")
                .param("runId", runId)
                .query((rs, rowNum) -> new Mismatch(
                        rs.getString("kind"),
                        rs.getObject("account_id", Long.class),
                        rs.getObject("transfer_id", Long.class),
                        rs.getObject("expected_cents", Long.class),
                        rs.getObject("actual_cents", Long.class),
                        rs.getString("detail")))
                .list();
    }

    private static ReconciliationRun mapRun(ResultSet rs) throws SQLException {
        return new ReconciliationRun(
                rs.getLong("id"),
                rs.getObject("started_at", OffsetDateTime.class).toInstant(),
                rs.getObject("finished_at", OffsetDateTime.class).toInstant(),
                rs.getLong("accounts_checked"),
                rs.getLong("transfers_checked"),
                rs.getInt("mismatch_count"),
                rs.getString("status"));
    }

    /** The PostgreSQL JDBC driver accepts OffsetDateTime for TIMESTAMPTZ, but not Instant. */
    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
