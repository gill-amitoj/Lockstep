package com.lockstep.ledger.web.dto;

import com.lockstep.ledger.domain.AuditEvent;

import java.time.Instant;

public record AuditEventResponse(
        Long id,
        Instant occurredAt,
        String actor,
        String action,
        String resourceType,
        String resourceId,
        String details,
        String correlationId) {

    public static AuditEventResponse from(AuditEvent event) {
        return new AuditEventResponse(
                event.getId(),
                event.getOccurredAt(),
                event.getActor(),
                event.getAction(),
                event.getResourceType(),
                event.getResourceId(),
                event.getDetails(),
                event.getCorrelationId());
    }
}
