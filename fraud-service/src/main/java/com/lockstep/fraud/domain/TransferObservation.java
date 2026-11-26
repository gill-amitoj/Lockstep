package com.lockstep.fraud.domain;

import com.lockstep.fraud.event.TransferCompletedEvent;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** A customer transfer this service has seen: the history the rules look back over. */
@Entity
@Table(name = "transfer_observations")
public class TransferObservation {

    @Id
    private Long transferId;

    private UUID eventId;

    private Long accountId;

    private long amountCents;

    private Instant occurredAt;

    protected TransferObservation() {
    }

    public TransferObservation(TransferCompletedEvent event) {
        this.transferId = event.transferId();
        this.eventId = event.eventId();
        this.accountId = event.fromAccountId();
        this.amountCents = event.amountCents();
        this.occurredAt = event.occurredAt();
    }

    public Long getTransferId() {
        return transferId;
    }

    public UUID getEventId() {
        return eventId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public long getAmountCents() {
        return amountCents;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
