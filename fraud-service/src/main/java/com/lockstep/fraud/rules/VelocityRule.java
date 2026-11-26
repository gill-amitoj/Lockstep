package com.lockstep.fraud.rules;

import com.lockstep.fraud.event.TransferCompletedEvent;
import com.lockstep.fraud.repository.TransferObservationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Rule (b): more than N transfers (default 5) from one account within a
 * window (default 10 minutes).
 *
 * The window is the 10 minutes ending at THIS transfer, (occurredAt - 10m,
 * occurredAt], counting this transfer itself. So the 6th transfer in 10
 * minutes is flagged, and so is every further one while the burst continues.
 */
@Component
public class VelocityRule implements FraudRule {

    public static final String NAME = "VELOCITY";

    private final TransferObservationRepository observations;
    private final long maxTransfers;
    private final Duration window;

    public VelocityRule(TransferObservationRepository observations,
                        @Value("${fraud.rules.velocity.max-transfers}") long maxTransfers,
                        @Value("${fraud.rules.velocity.window}") Duration window) {
        this.observations = observations;
        this.maxTransfers = maxTransfers;
        this.window = window;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Optional<String> evaluate(TransferCompletedEvent event) {
        long count = observations.countInWindow(
                event.fromAccountId(), event.occurredAt().minus(window), event.occurredAt());
        if (count > maxTransfers) {
            return Optional.of(count + " transfers from this account within " + window.toMinutes()
                    + " minutes (limit " + maxTransfers + ")");
        }
        return Optional.empty();
    }
}
