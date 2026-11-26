package com.lockstep.ledger.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** One row of the append-only audit log. No setters; the database rejects UPDATE and DELETE. */
@Entity
@Table(name = "audit_log")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Instant occurredAt;

    private String actor;

    private String action;

    private String resourceType;

    private String resourceId;

    private String details;

    private String correlationId;

    protected AuditEvent() {
    }

    public AuditEvent(String actor, String action, String resourceType, String resourceId,
                      String details, String correlationId) {
        this.occurredAt = Timestamps.now();
        this.actor = actor;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.details = details;
        this.correlationId = correlationId;
    }

    public Long getId() {
        return id;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getActor() {
        return actor;
    }

    public String getAction() {
        return action;
    }

    public String getResourceType() {
        return resourceType;
    }

    public String getResourceId() {
        return resourceId;
    }

    public String getDetails() {
        return details;
    }

    public String getCorrelationId() {
        return correlationId;
    }
}
