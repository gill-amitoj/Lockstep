package com.lockstep.fraud.event;

import com.lockstep.fraud.logging.CorrelationIdFilter;
import com.lockstep.fraud.service.FraudCheckService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

/**
 * Reads TransferCompleted events from Kafka.
 *
 * Events for one account share a Kafka key, so they sit in one partition and
 * reach ONE listener thread in order. That is why the rules can count an
 * account's history without locking: no two threads ever process the same
 * account at the same time.
 *
 * If this method throws, the record is retried and then sent to the
 * dead-letter topic (see KafkaConsumerConfig); it never blocks later events.
 */
@Component
public class TransferEventListener {

    private final FraudCheckService fraudCheckService;
    private final JsonMapper jsonMapper;

    public TransferEventListener(FraudCheckService fraudCheckService, JsonMapper jsonMapper) {
        this.fraudCheckService = fraudCheckService;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(topics = "${fraud.kafka.transfer-topic}", groupId = "${spring.kafka.consumer.group-id}")
    public void onTransferCompleted(ConsumerRecord<String, String> record) {
        // Carry the ledger request's correlation id over, so one id can be
        // followed from the HTTP request, through Kafka, into this service's logs.
        Header correlation = record.headers().lastHeader("correlationId");
        if (correlation != null) {
            MDC.put(CorrelationIdFilter.MDC_KEY, new String(correlation.value(), StandardCharsets.UTF_8));
        }
        try {
            TransferCompletedEvent event = jsonMapper.readValue(record.value(), TransferCompletedEvent.class);
            fraudCheckService.process(event);
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }
}
