package com.lockstep.fraud.service;

import com.lockstep.fraud.FraudIntegrationTest;
import com.lockstep.fraud.domain.FraudFlag;
import com.lockstep.fraud.event.TransferCompletedEvent;
import com.lockstep.fraud.repository.FraudFlagRepository;
import com.lockstep.fraud.repository.TransferObservationRepository;
import com.lockstep.fraud.rules.LargeAmountRule;
import com.lockstep.fraud.rules.VelocityRule;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The two rules and deduplication, called directly (no Kafka) against a real database. */
class FraudRulesTest extends FraudIntegrationTest {

    @Autowired
    private FraudCheckService fraudCheckService;

    @Autowired
    private FraudFlagRepository flags;

    @Autowired
    private TransferObservationRepository observations;

    // Spread history out so the velocity rule stays quiet while testing the amount rule.
    private static final Duration APART = Duration.ofHours(1);

    // ---- rule (a): amount > 5x the account's average ---------------------------

    @Test
    void amountMoreThanFiveTimesTheAverageIsFlagged() {
        long account = newAccountId();
        Instant t = Instant.now().minus(Duration.ofDays(1));
        process(transfer(account, 1_000, t));
        process(transfer(account, 3_000, t.plus(APART))); // average now 2_000

        List<FraudFlag> raised = process(transfer(account, 10_001, t.plus(APART.multipliedBy(2))));

        assertThat(raised).extracting(FraudFlag::getRule).containsExactly(LargeAmountRule.NAME);
        assertThat(raised.getFirst().getReason()).contains("average of 2000");
    }

    @Test
    void exactlyFiveTimesTheAverageIsNotFlagged() {
        long account = newAccountId();
        Instant t = Instant.now().minus(Duration.ofDays(1));
        process(transfer(account, 2_000, t));

        // "greater than 5x": 10_000 is exactly 5x, so it passes.
        assertThat(process(transfer(account, 10_000, t.plus(APART)))).isEmpty();
    }

    @Test
    void firstTransferHasNoAverageSoIsNeverFlaggedForAmount() {
        assertThat(process(transfer(newAccountId(), 99_999_999, Instant.now()))).isEmpty();
    }

    @Test
    void averageIgnoresOtherAccounts() {
        long quiet = newAccountId();
        long busy = newAccountId();
        Instant t = Instant.now().minus(Duration.ofDays(1));
        process(transfer(busy, 1_000_000, t));
        process(transfer(quiet, 100, t));

        // 600 is > 5x quiet's own average of 100, however big busy's transfers are.
        assertThat(process(transfer(quiet, 600, t.plus(APART))))
                .extracting(FraudFlag::getRule).containsExactly(LargeAmountRule.NAME);
    }

    // ---- rule (b): more than 5 transfers within 10 minutes ---------------------

    @Test
    void sixthTransferWithinTenMinutesIsFlagged() {
        long account = newAccountId();
        Instant t = Instant.now().minus(Duration.ofHours(2));
        for (int i = 0; i < 5; i++) {
            assertThat(process(transfer(account, 1_000, t.plus(Duration.ofMinutes(i))))).isEmpty();
        }

        List<FraudFlag> raised = process(transfer(account, 1_000, t.plus(Duration.ofMinutes(5))));

        assertThat(raised).extracting(FraudFlag::getRule).containsExactly(VelocityRule.NAME);
        assertThat(raised.getFirst().getReason()).startsWith("6 transfers");
    }

    @Test
    void transfersOutsideTheWindowDoNotCount() {
        long account = newAccountId();
        Instant t = Instant.now().minus(Duration.ofHours(2));
        for (int i = 0; i < 5; i++) {
            process(transfer(account, 1_000, t.plus(Duration.ofMinutes(i))));
        }

        // The first transfer was at t; this one is exactly 10 minutes later, so the
        // window (t, t+10m] no longer includes it: 5 transfers in the window, not 6.
        assertThat(process(transfer(account, 1_000, t.plus(Duration.ofMinutes(10))))).isEmpty();
    }

    @Test
    void oneTransferCanTripBothRules() {
        long account = newAccountId();
        Instant t = Instant.now().minus(Duration.ofHours(2));
        for (int i = 0; i < 5; i++) {
            process(transfer(account, 1_000, t.plus(Duration.ofMinutes(i))));
        }

        assertThat(process(transfer(account, 50_000, t.plus(Duration.ofMinutes(5)))))
                .extracting(FraudFlag::getRule)
                .containsExactlyInAnyOrder(LargeAmountRule.NAME, VelocityRule.NAME);
    }

    // ---- not applicable / dedupe -----------------------------------------------

    @Test
    void depositsFromTheSystemAccountAreIgnored() {
        long system = newAccountId();
        Instant t = Instant.now();
        for (int i = 0; i < 10; i++) {
            TransferCompletedEvent deposit = transfer(system, 1_000_000L * (i + 1), t.plusSeconds(i));
            deposit = new TransferCompletedEvent(deposit.eventId(), deposit.eventType(), deposit.occurredAt(),
                    deposit.transferId(), deposit.fromAccountId(), "SYSTEM", deposit.toAccountId(),
                    deposit.amountCents(), deposit.currency());
            assertThat(process(deposit)).isEmpty();
        }
        assertThat(flags.findByAccountIdOrderByIdDesc(system, Pageable.unpaged()))
                .isEmpty();
    }

    @Test
    void theSameEventProcessedTwiceCountsOnce() {
        long account = newAccountId();
        Instant t = Instant.now().minus(Duration.ofDays(1));
        process(transfer(account, 100, t));
        TransferCompletedEvent big = transfer(account, 10_000, t.plus(APART));

        assertThat(process(big)).hasSize(1);
        assertThat(process(big)).isEmpty(); // redelivered: skipped

        assertThat(flags.findByTransferId(big.transferId())).hasSize(1);
        assertThat(observations.countEarlier(account, -1L, Instant.now())).isEqualTo(2);
    }

    private List<FraudFlag> process(TransferCompletedEvent event) {
        return fraudCheckService.process(event);
    }
}
