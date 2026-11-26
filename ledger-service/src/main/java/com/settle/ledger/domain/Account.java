package com.settle.ledger.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * An account and its current balance.
 *
 * The balance column is a running total kept in the same transaction as the
 * entries, so reading a balance is one row lookup instead of a SUM over every
 * entry. The entries remain the source of truth; the reconciliation job
 * (Phase 4) checks the two always agree.
 *
 * Column names: Spring Boot maps camelCase fields to snake_case columns
 * (ownerId -> owner_id), so no @Column annotations are needed.
 */
@Entity
@Table(name = "accounts")
public class Account {

    public static final String CURRENCY = "CAD";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String ownerId;

    @Enumerated(EnumType.STRING)
    private AccountType type;

    private String currency;

    private long balanceCents;

    private Instant createdAt;

    /** Required by JPA. Not for application code. */
    protected Account() {
    }

    public Account(String ownerId, AccountType type) {
        this.ownerId = ownerId;
        this.type = type;
        this.currency = CURRENCY;
        this.balanceCents = 0;
        this.createdAt = Timestamps.now();
    }

    /** Customer accounts need enough money; the SYSTEM funding account may go negative. */
    public boolean canDebit(long amountCents) {
        return type == AccountType.SYSTEM || balanceCents >= amountCents;
    }

    public void debit(long amountCents) {
        // subtractExact/addExact throw instead of silently wrapping around on overflow.
        balanceCents = Math.subtractExact(balanceCents, amountCents);
    }

    public void credit(long amountCents) {
        balanceCents = Math.addExact(balanceCents, amountCents);
    }

    public Long getId() {
        return id;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public AccountType getType() {
        return type;
    }

    public String getCurrency() {
        return currency;
    }

    public long getBalanceCents() {
        return balanceCents;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
