package com.lockstep.fraud.event;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;

/**
 * What happens when processing a message fails.
 *
 * Retry it a few times (a database blip may pass), then publish it to a
 * dead-letter topic (DLT) and move on. Without this, one bad message (a "poison
 * pill") would be retried forever and block every event behind it in its
 * partition. Messages that can never succeed, like unparseable JSON, skip the
 * retries and go straight to the DLT, where a person can inspect and replay them.
 */
@Configuration
public class KafkaConsumerConfig {

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> kafkaTemplate,
                                          @Value("${fraud.kafka.dead-letter-suffix}") String dltSuffix) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                // Partition -1: let Kafka choose (the DLT may have a different partition count).
                (record, exception) -> new TopicPartition(record.topic() + dltSuffix, -1));
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(500L, 2));
        handler.addNotRetryableExceptions(JacksonException.class);
        return handler;
    }

    /**
     * Same definition as the ledger's. Whichever service starts first creates it;
     * otherwise a consumer that starts first can make the broker auto-create the
     * topic with its default of ONE partition.
     */
    @Bean
    NewTopic transferTopic(@Value("${fraud.kafka.transfer-topic}") String topic) {
        return TopicBuilder.name(topic).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic transferTopicDeadLetter(@Value("${fraud.kafka.transfer-topic}") String topic,
                                     @Value("${fraud.kafka.dead-letter-suffix}") String dltSuffix) {
        return TopicBuilder.name(topic + dltSuffix).partitions(1).replicas(1).build();
    }
}
