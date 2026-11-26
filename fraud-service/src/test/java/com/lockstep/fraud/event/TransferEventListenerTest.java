package com.lockstep.fraud.event;

import com.lockstep.fraud.FraudIntegrationTest;
import com.lockstep.fraud.repository.FraudFlagRepository;
import com.lockstep.fraud.repository.ProcessedEventRepository;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Messages published to the real Kafka topic are consumed, deduplicated and dead-lettered as designed. */
class TransferEventListenerTest extends FraudIntegrationTest {

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private KafkaContainer kafkaContainer;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private FraudFlagRepository flags;

    @Autowired
    private ProcessedEventRepository processedEvents;

    @Value("${fraud.kafka.transfer-topic}")
    private String topic;

    @Test
    void eventsOnTheTopicAreConsumedAndFlagged() throws Exception {
        long account = newAccountId();
        Instant t = Instant.now().minus(Duration.ofDays(1));
        publish(transfer(account, 100, t));
        TransferCompletedEvent big = transfer(account, 5_000, t.plus(Duration.ofHours(1)));
        publish(big);

        eventually(() -> !flags.findByTransferId(big.transferId()).isEmpty());
        assertThat(flags.findByTransferId(big.transferId()).getFirst().getRule()).isEqualTo("LARGE_AMOUNT");
    }

    /** At-least-once delivery means duplicates WILL arrive; they must not double-count. */
    @Test
    void duplicateDeliveriesAreProcessedOnce() throws Exception {
        long account = newAccountId();
        Instant t = Instant.now().minus(Duration.ofDays(1));
        publish(transfer(account, 100, t));
        TransferCompletedEvent big = transfer(account, 5_000, t.plus(Duration.ofHours(1)));
        publish(big);
        publish(big);
        publish(big);
        TransferCompletedEvent marker = transfer(account, 1, t.plus(Duration.ofHours(2)));
        publish(marker); // same key = same partition, so once it's processed, the duplicates were too

        eventually(() -> processedEvents.existsById(marker.eventId()));
        assertThat(flags.findByTransferId(big.transferId())).hasSize(1);
    }

    /** A poison message goes to the dead-letter topic and does NOT block the events behind it. */
    @Test
    void unparseableMessageIsDeadLetteredAndLaterEventsStillFlow() throws Exception {
        long account = newAccountId();
        String key = String.valueOf(account);
        kafka.send(new ProducerRecord<>(topic, key, "{this is not json")).get();
        Instant t = Instant.now().minus(Duration.ofDays(1));
        publish(transfer(account, 100, t));
        TransferCompletedEvent big = transfer(account, 5_000, t.plus(Duration.ofHours(1)));
        publish(big);

        eventually(() -> !flags.findByTransferId(big.transferId()).isEmpty());
        assertThat(readDeadLetters()).anySatisfy(record -> {
            assertThat(record.key()).isEqualTo(key);
            assertThat(record.value()).isEqualTo("{this is not json");
        });
    }

    private void publish(TransferCompletedEvent event) throws Exception {
        // Keyed by paying account, exactly as the ledger's outbox relay does.
        kafka.send(new ProducerRecord<>(topic, String.valueOf(event.fromAccountId()),
                jsonMapper.writeValueAsString(event))).get();
    }

    private List<ConsumerRecord<String, String>> readDeadLetters() {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(topic + ".DLT"));
            List<ConsumerRecord<String, String>> records = new ArrayList<>();
            Instant deadline = Instant.now().plusSeconds(10);
            while (records.isEmpty() && Instant.now().isBefore(deadline)) {
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
            }
            return records;
        }
    }
}
