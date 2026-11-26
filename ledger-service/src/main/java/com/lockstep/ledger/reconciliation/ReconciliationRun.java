package com.lockstep.ledger.reconciliation;

import java.time.Instant;

/** Summary of one reconciliation run. Also the JSON shape returned by the admin API. */
public record ReconciliationRun(
        long id,
        Instant startedAt,
        Instant finishedAt,
        long accountsChecked,
        long transfersChecked,
        int mismatchCount,
        String status) {
}
