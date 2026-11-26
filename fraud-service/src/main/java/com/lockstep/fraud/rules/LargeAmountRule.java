package com.lockstep.fraud.rules;

import com.lockstep.fraud.event.TransferCompletedEvent;
import com.lockstep.fraud.repository.TransferObservationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Rule (a): the amount is more than N times (default 5) the account's average
 * earlier transfer.
 *
 * The comparison stays in whole numbers:
 *     amount > multiplier * (sum / count)   <=>   amount * count > multiplier * sum
 * so there is no rounding of the average (and no floating point near money).
 *
 * With no earlier transfers there is no average, so the rule can't apply.
 * min-history (default 1) sets how many earlier transfers are needed; raising
 * it trades missed fraud on new accounts for fewer false alarms.
 */
@Component
public class LargeAmountRule implements FraudRule {

    public static final String NAME = "LARGE_AMOUNT";

    private final TransferObservationRepository observations;
    private final long multiplier;
    private final long minHistory;

    public LargeAmountRule(TransferObservationRepository observations,
                           @Value("${fraud.rules.large-amount.multiplier}") long multiplier,
                           @Value("${fraud.rules.large-amount.min-history}") long minHistory) {
        this.observations = observations;
        this.multiplier = multiplier;
        this.minHistory = minHistory;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Optional<String> evaluate(TransferCompletedEvent event) {
        long count = observations.countEarlier(event.fromAccountId(), event.transferId(), event.occurredAt());
        if (count < minHistory) {
            return Optional.empty();
        }
        long sum = observations.sumEarlier(event.fromAccountId(), event.transferId(), event.occurredAt());

        // multiplyExact throws instead of silently overflowing on absurd amounts.
        if (Math.multiplyExact(event.amountCents(), count) > Math.multiplyExact(multiplier, sum)) {
            long averageCents = sum / count;
            return Optional.of("Amount " + event.amountCents() + " is more than " + multiplier
                    + "x the account's average of " + averageCents + " over " + count + " earlier transfers");
        }
        return Optional.empty();
    }
}
