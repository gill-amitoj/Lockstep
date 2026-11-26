package com.lockstep.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * A remembered request/response pair for one (user, Idempotency-Key).
 * Rows are inserted and completed with SQL in IdempotencyKeyRepository; this
 * entity is only used to read them back.
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKey {

    /** Two-column primary key: the same key string from two different users is two different rows. */
    @EmbeddedId
    private Id id;

    private String requestHash;

    private Integer responseStatus;

    private String responseBody;

    private Long transferId;

    private Instant createdAt;

    protected IdempotencyKey() {
    }

    public Id getId() {
        return id;
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

    /** JPA requires composite ids to be Serializable and to implement equals/hashCode. */
    @Embeddable
    public static class Id implements Serializable {

        private String clientId;

        @Column(name = "idempotency_key")
        private String key;

        protected Id() {
        }

        public Id(String clientId, String key) {
            this.clientId = clientId;
            this.key = key;
        }

        public String getClientId() {
            return clientId;
        }

        public String getKey() {
            return key;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Id that && clientId.equals(that.clientId) && key.equals(that.key);
        }

        @Override
        public int hashCode() {
            return Objects.hash(clientId, key);
        }
    }
}
