package com.settle.ledger.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One movement of money between two accounts. Immutable: there are no setters,
 * and a database trigger rejects UPDATE and DELETE on the table.
 *
 * Accounts are referenced by id (a plain Long) rather than a JPA relationship
 * (@ManyToOne). That keeps loading explicit: nothing is fetched behind our back.
 */
@Entity
@Table(name = "transfers")
public class Transfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long fromAccountId;

    private Long toAccountId;

    private long amountCents;

    private String currency;

    private Instant createdAt;

    protected Transfer() {
    }

    public Transfer(Long fromAccountId, Long toAccountId, long amountCents) {
        this.fromAccountId = fromAccountId;
        this.toAccountId = toAccountId;
        this.amountCents = amountCents;
        this.currency = Account.CURRENCY;
        this.createdAt = Timestamps.now();
    }

    public Long getId() {
        return id;
    }

    public Long getFromAccountId() {
        return fromAccountId;
    }

    public Long getToAccountId() {
        return toAccountId;
    }

    public long getAmountCents() {
        return amountCents;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
