package com.settle.ledger.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One line in the ledger: a signed change to one account's balance.
 * Negative = debit (money out), positive = credit (money in).
 * Every transfer creates exactly two entries that sum to zero.
 */
@Entity
@Table(name = "entries")
public class Entry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long transferId;

    private Long accountId;

    private long amountCents;

    private Instant createdAt;

    protected Entry() {
    }

    private Entry(Long transferId, Long accountId, long amountCents) {
        this.transferId = transferId;
        this.accountId = accountId;
        this.amountCents = amountCents;
        this.createdAt = Instant.now();
    }

    public static Entry debit(Long transferId, Long accountId, long amountCents) {
        return new Entry(transferId, accountId, -amountCents);
    }

    public static Entry credit(Long transferId, Long accountId, long amountCents) {
        return new Entry(transferId, accountId, amountCents);
    }

    public Long getId() {
        return id;
    }

    public Long getTransferId() {
        return transferId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public long getAmountCents() {
        return amountCents;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
