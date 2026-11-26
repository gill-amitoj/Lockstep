package com.lockstep.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A remembered request/response pair for one Idempotency-Key.
 * Rows are inserted and completed with SQL in IdempotencyKeyRepository; this
 * entity is only used to read them back.
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKey {

    @Id
    @Column(name = "idempotency_key")
    private String key;

    private String requestHash;

    private Integer responseStatus;

    private String responseBody;

    private Long transferId;

    private Instant createdAt;

    protected IdempotencyKey() {
    }

    public String getKey() {
        return key;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public Long getTransferId() {
        return transferId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
