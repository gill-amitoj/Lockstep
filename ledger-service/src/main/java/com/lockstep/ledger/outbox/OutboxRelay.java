package com.lockstep.ledger.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Moves events from the outbox table to Kafka.
 *
 * For each batch, in one database transaction:
 *   1. lock the oldest unpublished rows (SKIP LOCKED, so instances share work),
 *   2. send each to Kafka and WAIT for the broker to acknowledge it,
 *   3. mark it published.
 * If a send fails, the exception rolls the transaction back: rows already sent
 * in this batch go back to "unpublished" and are sent AGAIN next time. That's
 * the deliberate trade-off: duplicates are possible, losses are not. The
 * consumer deduplicates on eventId.
 *
 * Trade-off: the database transaction stays open while waiting for Kafka (a
 * few milliseconds normally, up to the send timeout during an outage). Fine at
 * this scale; a high-volume system would claim rows in one short transaction
 * and mark them in another, or use change-data-capture (e.g. Debezium).
 */
@Service
public class OutboxRelay {

    static final String EVENT_ID_HEADER = "eventId";
    static final String EVENT_TYPE_HEADER = "eventType";
    static final String CORRELATION_ID_HEADER = "correlationId";

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final int batchSize;
    private final long sendTimeoutMs;

    public OutboxRelay(OutboxEventRepository outbox, KafkaTemplate<String, String> kafka,
                       @Value("${lockstep.outbox.batch-size}") int batchSize,
                       @Value("${lockstep.outbox.send-timeout-ms}") long sendTimeoutMs) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.batchSize = batchSize;
        this.sendTimeoutMs = sendTimeoutMs;
    }

    /** @return how many events were published */
    @Transactional
    public int publishBatch() {
        List<OutboxEvent> batch = outbox.lockNextUnpublished(batchSize);
        for (OutboxEvent event : batch) {
            send(event);
            event.markPublished(); // dirty checking writes this UPDATE at commit
        }
        if (!batch.isEmpty()) {
            log.atInfo().addKeyValue("count", batch.size()).log("Published outbox events");
        }
        return batch.size();
    }

    private void send(OutboxEvent event) {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(event.getTopic(), event.getMessageKey(), event.getPayload());
        header(record, EVENT_ID_HEADER, event.getEventId().toString());
        header(record, EVENT_TYPE_HEADER, event.getEventType());
        header(record, CORRELATION_ID_HEADER, event.getCorrelationId());
        try {
            // .get(): block until the broker confirms the write (acks=all), so we
            // never mark a row published that Kafka doesn't actually have.
            kafka.send(record).get(sendTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OutboxPublishException(event, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new OutboxPublishException(event, e);
        }
    }

    private static void header(ProducerRecord<String, String> record, String name, String value) {
        if (value != null) {
            record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
        }
    }

    static class OutboxPublishException extends RuntimeException {
        OutboxPublishException(OutboxEvent event, Throwable cause) {
            super("Failed to publish outbox event " + event.getEventId(), cause);
        }
    }
}
