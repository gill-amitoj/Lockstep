package com.lockstep.ledger.outbox;

import com.jayway.jsonpath.JsonPath;
import com.lockstep.ledger.IntegrationTest;
import com.lockstep.ledger.domain.Account;
import com.lockstep.ledger.domain.Transfer;
import com.lockstep.ledger.service.InsufficientFundsException;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.testcontainers.kafka.KafkaContainer;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/** The ledger half of the outbox pattern, against real PostgreSQL and a real Kafka broker. */
class OutboxTest extends IntegrationTest {

    @Autowired
    private OutboxEventRepository outbox;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private KafkaContainer kafka;

    @Autowired
    private DataSource dataSource;

    @Value("${lockstep.kafka.transfer-topic}")
    private String topic;

    @Test
    void transferWritesExactlyOneUnpublishedEventWithTheRightPayload() {
        Account alice = openFundedAccount(1_000);
        Account bob = openFundedAccount(0);

        Transfer transfer = transferService.transfer(ownerOf(alice), alice.getId(), bob.getId(), 250L);

        OutboxEvent event = eventFor(transfer);
        assertThat(event.getPublishedAt()).isNull(); // committed, but not yet sent
        assertThat(event.getEventType()).isEqualTo("TransferCompleted");
        assertThat(event.getMessageKey()).isEqualTo(alice.getId().toString());
        String payload = event.getPayload();
        assertThat((String) JsonPath.read(payload, "$.eventId")).isEqualTo(event.getEventId().toString());
        assertThat(((Number) JsonPath.read(payload, "$.transferId")).longValue()).isEqualTo(transfer.getId());
        assertThat(((Number) JsonPath.read(payload, "$.amountCents")).longValue()).isEqualTo(250);
        assertThat((String) JsonPath.read(payload, "$.fromAccountType")).isEqualTo("CUSTOMER");
        assertThat((String) JsonPath.read(payload, "$.currency")).isEqualTo("CAD");
    }

    /** Same transaction: no transfer, no event. (Calling Kafka directly could announce a transfer that rolled back.) */
    @Test
    void rejectedTransferWritesNoEvent() {
        Account alice = openFundedAccount(100);
        Account bob = openFundedAccount(0);
        long before = outbox.count();

        assertThatThrownBy(() -> transferService.transfer(ownerOf(alice), alice.getId(), bob.getId(), 999L))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(outbox.count()).isEqualTo(before);
    }

    /**
     * "The ledger crashed right after committing" is exactly the state a fresh
     * transfer is in before the relay runs: the event exists only in the outbox.
     * When the relay next runs (e.g. after a restart), the event reaches Kafka.
     */
    @Test
    void relayPublishesToKafkaWithKeyAndHeadersThenMarksPublished() {
        Account alice = openFundedAccount(1_000);
        Account bob = openFundedAccount(0);
        Transfer transfer = transferService.transfer(ownerOf(alice), alice.getId(), bob.getId(), 100L);
        OutboxEvent event = eventFor(transfer);

        drainOutbox();

        assertThat(outbox.findById(event.getEventId()).orElseThrow().getPublishedAt()).isNotNull();
        ConsumerRecord<String, String> record = consumeEvent(event.getEventId());
        assertThat(record.key()).isEqualTo(alice.getId().toString());
        assertThat(record.value()).isEqualTo(event.getPayload());
        assertThat(header(record, OutboxRelay.EVENT_ID_HEADER)).isEqualTo(event.getEventId().toString());
        assertThat(header(record, OutboxRelay.EVENT_TYPE_HEADER)).isEqualTo("TransferCompleted");
    }

    /** Kafka down: the relay fails, nothing is marked published, and nothing is lost. */
    @Test
    void kafkaOutageLeavesEventsUnpublishedUntilKafkaIsBack() {
        Account alice = openFundedAccount(1_000);
        Account bob = openFundedAccount(0);
        drainOutbox(); // start from an empty backlog
        Transfer transfer = transferService.transfer(ownerOf(alice), alice.getId(), bob.getId(), 100L);

        // Freeze the broker's processes: it stops answering, like a crashed or partitioned broker.
        kafka.getDockerClient().pauseContainerCmd(kafka.getContainerId()).exec();
        try {
            assertThatThrownBy(() -> relay.publishBatch()).isInstanceOf(RuntimeException.class);
            assertThat(eventFor(transfer).getPublishedAt()).isNull(); // the rollback undid nothing but the attempt
        } finally {
            kafka.getDockerClient().unpauseContainerCmd(kafka.getContainerId()).exec();
        }

        drainOutboxWithRetries(); // producer reconnects after the pause
        assertThat(eventFor(transfer).getPublishedAt()).isNotNull();
        assertThat(consumeEvent(eventFor(transfer).getEventId())).isNotNull();
    }

    /** Two relay instances never send the same row: rows locked by one are skipped by the other. */
    @Test
    void rowsLockedByAnotherRelayAreSkipped() throws Exception {
        Account alice = openFundedAccount(1_000);
        Account bob = openFundedAccount(0);
        drainOutbox();
        Transfer transfer = transferService.transfer(ownerOf(alice), alice.getId(), bob.getId(), 100L);
        UUID eventId = eventFor(transfer).getEventId();

        try (Connection otherRelay = dataSource.getConnection()) {
            otherRelay.setAutoCommit(false);
            try (PreparedStatement lock = otherRelay.prepareStatement(
                    "SELECT event_id FROM outbox_events WHERE event_id = ? FOR UPDATE")) {
                lock.setObject(1, eventId);
                lock.executeQuery();
            }

            assertThat(relay.publishBatch()).isZero(); // skipped, didn't wait
            assertThat(outbox.findById(eventId).orElseThrow().getPublishedAt()).isNull();

            otherRelay.rollback();
        }
        assertThat(relay.publishBatch()).isEqualTo(1);
    }

    private OutboxEvent eventFor(Transfer transfer) {
        List<OutboxEvent> events = outbox.findByAggregateTypeAndAggregateId("transfer", transfer.getId().toString());
        assertThat(events).hasSize(1);
        return events.getFirst();
    }

    /** Other tests leave unpublished rows behind; publish everything. */
    private void drainOutbox() {
        while (relay.publishBatch() > 0) {
            // keep going until the backlog is empty
        }
        assertThat(outbox.countByPublishedAtIsNull()).isZero();
    }

    private void drainOutboxWithRetries() {
        Instant deadline = Instant.now().plusSeconds(30);
        while (true) {
            try {
                drainOutbox();
                return;
            } catch (RuntimeException e) {
                if (Instant.now().isAfter(deadline)) {
                    throw e;
                }
            }
        }
    }

    /** Reads the topic from the beginning with a throwaway consumer group until the event shows up. */
    private ConsumerRecord<String, String> consumeEvent(UUID eventId) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "outbox-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(topic));
            Instant deadline = Instant.now().plusSeconds(20);
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (eventId.toString().equals(header(record, OutboxRelay.EVENT_ID_HEADER))) {
                        return record;
                    }
                }
            }
        }
        return fail("Event " + eventId + " never arrived on " + topic);
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
