package com.lockstep.fraud.domain;

import com.lockstep.fraud.event.TransferCompletedEvent;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** A transfer that a rule considered suspicious. A flag is a signal for a human to review, not a block. */
@Entity
@Table(name = "fraud_flags")
public class FraudFlag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private UUID eventId;

    private Long transferId;

    private Long accountId;

    private String rule;

    private String reason;

    private long amountCents;

    private Instant occurredAt;

    private Instant flaggedAt;

    protected FraudFlag() {
    }

    public FraudFlag(TransferCompletedEvent event, String rule, String reason) {
        this.eventId = event.eventId();
        this.transferId = event.transferId();
        this.accountId = event.fromAccountId();
        this.rule = rule;
        this.reason = reason;
        this.amountCents = event.amountCents();
        this.occurredAt = event.occurredAt();
        this.flaggedAt = Instant.now().truncatedTo(ChronoUnit.MICROS); // Postgres keeps microseconds
    }

    public Long getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public Long getTransferId() {
        return transferId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public String getRule() {
        return rule;
    }

    public String getReason() {
        return reason;
    }

    public long getAmountCents() {
        return amountCents;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getFlaggedAt() {
        return flaggedAt;
    }
}
