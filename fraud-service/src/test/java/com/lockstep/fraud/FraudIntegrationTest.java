package com.lockstep.fraud;

import com.lockstep.fraud.event.TransferCompletedEvent;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;

/**
 * Base class: full fraud-service context + real PostgreSQL + real Kafka, shared
 * by all test classes. Each test uses fresh random account ids, so tests
 * never see each other's history.
 */
@SpringBootTest(properties = "fraud.jwt.secret=" + FraudIntegrationTest.TEST_SECRET)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public abstract class FraudIntegrationTest {

    /** Test-only key; exists only inside throwaway containers. */
    static final String TEST_SECRET = "test-only-secret-do-not-use-anywhere-else-0123456789";

    private static long nextTransferId = ThreadLocalRandom.current().nextLong(1_000_000, 1_000_000_000);

    protected static long newAccountId() {
        return ThreadLocalRandom.current().nextLong(1_000_000, Long.MAX_VALUE / 2);
    }

    /** A CUSTOMER transfer event, as the ledger would publish it. */
    protected static TransferCompletedEvent transfer(long fromAccountId, long amountCents, Instant occurredAt) {
        return new TransferCompletedEvent(UUID.randomUUID(), TransferCompletedEvent.TYPE,
                occurredAt.truncatedTo(ChronoUnit.MICROS), nextTransferId++, fromAccountId, "CUSTOMER",
                42L, amountCents, "CAD");
    }

    /** A token exactly like the ledger issues: same secret, the ledger's issuer. */
    protected static String bearer(String username, String role) {
        NimbusJwtEncoder encoder = NimbusJwtEncoder.withSecretKey(
                        new SecretKeySpec(TEST_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
                .algorithm(MacAlgorithm.HS256)
                .build();
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("lockstep-ledger")
                .subject(username)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .claim("roles", List.of(role))
                .build();
        return "Bearer " + encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    /** Consumption is asynchronous: poll until the condition holds or time runs out. */
    protected static void eventually(BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(30);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Condition not met within 30 seconds");
            }
            Thread.sleep(200);
        }
    }
}
