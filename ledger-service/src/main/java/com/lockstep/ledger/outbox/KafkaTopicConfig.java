package com.lockstep.ledger.outbox;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Spring's KafkaAdmin creates this topic at startup if it doesn't exist.
 * 3 partitions: up to 3 fraud-service consumers can share the work, while each
 * account's events (same key) still land in one partition, in order.
 */
@Configuration
public class KafkaTopicConfig {

    @Bean
    NewTopic transferCompletedTopic(@Value("${lockstep.kafka.transfer-topic}") String topic) {
        return TopicBuilder.name(topic).partitions(3).replicas(1).build();
    }
}
