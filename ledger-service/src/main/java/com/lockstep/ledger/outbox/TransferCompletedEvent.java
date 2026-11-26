package com.lockstep.ledger.outbox;

import java.time.Instant;
import java.util.UUID;

/**
 * The contract published to Kafka when a transfer commits. Other services
 * depend on this JSON shape, so fields are only ever ADDED, never renamed or
 * removed (consumers ignore fields they don't know).
 *
 * fromAccountType lets consumers tell customer payments apart from bank
 * deposits out of the SYSTEM account.
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
