package com.lockstep.fraud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The fraud-check service: a separate application with its own database. It
 * never calls the ledger and the ledger never calls it; the only link is the
 * Kafka topic. If this service is down or slow, transfers carry on unaffected,
 * and it catches up from Kafka when it comes back.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class FraudServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FraudServiceApplication.class, args);
    }
}
