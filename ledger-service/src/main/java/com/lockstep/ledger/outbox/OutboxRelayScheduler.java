package com.lockstep.ledger.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls the outbox. Separate from OutboxRelay so tests can switch the timer off
 * and call publishBatch() at exactly the moment they choose.
 */
@Component
@ConditionalOnProperty(name = "lockstep.outbox.relay.scheduled", havingValue = "true", matchIfMissing = true)
public class OutboxRelayScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayScheduler.class);

    private final OutboxRelay relay;

    public OutboxRelayScheduler(OutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${lockstep.outbox.poll-interval}")
    public void poll() {
        try {
            // Keep draining while full batches come back, so a backlog clears quickly.
            int published;
            do {
                published = relay.publishBatch();
            } while (published > 0);
        } catch (RuntimeException e) {
            // Kafka down, etc. Nothing is lost: the rows stay unpublished and the next poll retries.
            log.warn("Outbox relay failed; will retry on next poll: {}", e.getMessage());
        }
    }
}
