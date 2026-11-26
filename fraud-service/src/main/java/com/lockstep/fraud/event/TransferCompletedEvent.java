package com.lockstep.fraud.event;

import java.time.Instant;
import java.util.UUID;

/**
 * The fraud service's own copy of the ledger's event contract.
 *
 * Deliberately duplicated rather than shared through a common library: a
 * shared jar would couple the two services' release cycles. The JSON is the
 * contract, and unknown fields are ignored, so the ledger can add fields
 * without breaking this service.
 */
public record TransferCompletedEvent(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        long transferId,
        long fromAccountId,
        String fromAccountType,
        long toAccountId,
        long amountCents,
        String currency) {

    public static final String TYPE = "TransferCompleted";
}
