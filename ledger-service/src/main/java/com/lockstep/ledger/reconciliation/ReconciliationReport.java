package com.lockstep.ledger.reconciliation;

import java.util.List;

public record ReconciliationReport(ReconciliationRun run, List<Mismatch> mismatches) {
}
