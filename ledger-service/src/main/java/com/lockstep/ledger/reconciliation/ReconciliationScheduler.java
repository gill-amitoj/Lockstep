package com.lockstep.ledger.reconciliation;

import com.lockstep.ledger.logging.CorrelationIdFilter;
import com.lockstep.ledger.service.AuditService;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Runs reconciliation on a timer. Kept separate from ReconciliationService so
 * the timer can be switched off (tests do, to control exactly when runs happen)
 * while the service stays available to the admin API.
 *
 * fixedDelay (not fixedRate): the next run starts a full interval after the
 * previous one FINISHES, so slow runs can never pile up.
 */
@Component
@ConditionalOnProperty(name = "lockstep.reconciliation.scheduled", havingValue = "true", matchIfMissing = true)
public class ReconciliationScheduler {

    private final ReconciliationService reconciliationService;

    public ReconciliationScheduler(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @Scheduled(initialDelayString = "${lockstep.reconciliation.initial-delay}",
               fixedDelayString = "${lockstep.reconciliation.interval}")
    public void runScheduled() {
        // No HTTP request here, so no CorrelationIdFilter: give the run its own id
        // so all of its log lines and its audit record can still be tied together.
        MDC.put(CorrelationIdFilter.MDC_KEY, "reconciliation-" + UUID.randomUUID());
        try {
            reconciliationService.run(AuditService.SCHEDULER_ACTOR);
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }
}
