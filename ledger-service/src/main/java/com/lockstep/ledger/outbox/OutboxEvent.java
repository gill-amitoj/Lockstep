package com.lockstep.ledger.outbox;

import com.lockstep.ledger.domain.Timestamps;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One row of the outbox: an event waiting to be (or already) published to Kafka. */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    /** Generated in Java (not by the database) so it is known before the insert and can go in the payload. */
    @Id
    private UUID eventId;

    private String aggregateType;

    private String aggregateId;

    private String eventType;

    private String topic;

    private String messageKey;

    private String payload;

    private String correlationId;

    private Instant createdAt;

    private Instant publishedAt;

    protected OutboxEvent() {
    }

    public OutboxEvent(UUID eventId, String aggregateType, String aggregateId, String eventType,
                       String topic, String messageKey, String payload, String correlationId) {
        this.eventId = eventId;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.topic = topic;
        this.messageKey = messageKey;
        this.payload = payload;
        this.correlationId = correlationId;
        this.createdAt = Timestamps.now();
    }

    public void markPublished() {
        this.publishedAt = Timestamps.now();
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getTopic() {
        return topic;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public String getPayload() {
        return payload;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}
