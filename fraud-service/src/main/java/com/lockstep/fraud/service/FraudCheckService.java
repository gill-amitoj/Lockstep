package com.lockstep.fraud.service;

import com.lockstep.fraud.domain.FraudFlag;
import com.lockstep.fraud.domain.TransferObservation;
import com.lockstep.fraud.event.TransferCompletedEvent;
import com.lockstep.fraud.repository.FraudFlagRepository;
import com.lockstep.fraud.repository.ProcessedEventRepository;
import com.lockstep.fraud.repository.TransferObservationRepository;
import com.lockstep.fraud.rules.FraudRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Processes one TransferCompleted event, exactly once in effect.
 *
 * Everything happens in ONE database transaction: mark the event processed,
 * record the observation, run the rules, save any flags. Kafka's offset is
 * committed only AFTER this method returns. So:
 *  - crash before the DB commit: nothing was saved, Kafka redelivers, we process it fresh;
 *  - crash after the DB commit but before the offset commit: Kafka redelivers,
 *    markProcessed returns 0, and we skip it.
 * Either way the event is applied once. (Kafka itself is at-least-once; the
 * dedupe table is what makes the effect exactly-once.)
 */
@Service
public class FraudCheckService {

    private static final Logger log = LoggerFactory.getLogger(FraudCheckService.class);

    private final ProcessedEventRepository processedEvents;
    private final TransferObservationRepository observations;
    private final FraudFlagRepository flags;
    private final List<FraudRule> rules;

    /** Spring injects every FraudRule bean into the list. */
    public FraudCheckService(ProcessedEventRepository processedEvents, TransferObservationRepository observations,
                             FraudFlagRepository flags, List<FraudRule> rules) {
        this.processedEvents = processedEvents;
        this.observations = observations;
        this.flags = flags;
        this.rules = rules;
    }

    /** @return the flags raised (empty if none, or if the event was a duplicate or not applicable) */
    @Transactional
    public List<FraudFlag> process(TransferCompletedEvent event) {
        if (processedEvents.markProcessed(event.eventId()) == 0) {
            log.atInfo().addKeyValue("eventId", event.eventId()).log("Duplicate event skipped");
            return List.of();
        }
        if (!TransferCompletedEvent.TYPE.equals(event.eventType())) {
            log.atInfo().addKeyValue("eventType", event.eventType()).log("Ignoring unknown event type");
            return List.of();
        }
        // Deposits come out of the bank's SYSTEM account. They are not customer
        // behaviour, and the SYSTEM account would trip the velocity rule all day.
        if ("SYSTEM".equals(event.fromAccountType())) {
            return List.of();
        }

        // Record first, so the rules see this transfer as part of the history
        // (the velocity window counts it; the average rule explicitly excludes it).
        // saveAndFlush: the rules' COUNT queries must see this row.
        observations.saveAndFlush(new TransferObservation(event));

        List<FraudFlag> raised = new ArrayList<>();
        for (FraudRule rule : rules) {
            Optional<String> reason = rule.evaluate(event);
            if (reason.isPresent()) {
                raised.add(flags.save(new FraudFlag(event, rule.name(), reason.get())));
                log.atWarn()
                        .addKeyValue("rule", rule.name())
                        .addKeyValue("transferId", event.transferId())
                        .addKeyValue("accountId", event.fromAccountId())
                        .log("Transfer flagged: {}", reason.get());
            }
        }
        return raised;
    }
}
