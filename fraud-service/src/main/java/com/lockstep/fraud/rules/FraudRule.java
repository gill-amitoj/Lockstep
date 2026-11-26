package com.lockstep.fraud.rules;

import com.lockstep.fraud.event.TransferCompletedEvent;

import java.util.Optional;

/**
 * One fraud check. Each rule is a separate Spring bean; FraudCheckService runs
 * all of them, so adding a rule means adding a class, not editing existing ones.
 *
 * Rules run AFTER the current transfer has been recorded as an observation,
 * and look back from the transfer's own occurredAt (event time). Using event
 * time instead of "now" makes results the same whether an event is processed
 * immediately or replayed hours later after an outage.
 */
public interface FraudRule {

    String name();

    /** @return a human-readable reason if the transfer is suspicious, else empty */
    Optional<String> evaluate(TransferCompletedEvent event);
}
