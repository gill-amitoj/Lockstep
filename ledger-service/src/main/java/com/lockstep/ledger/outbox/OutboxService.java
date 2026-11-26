package com.lockstep.ledger.outbox;

import com.lockstep.ledger.domain.Account;
import com.lockstep.ledger.domain.Transfer;
import com.lockstep.ledger.logging.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/** Writes events into the outbox. Publishing to Kafka is OutboxRelay's job, not this class's. */
@Service
public class OutboxService {

    private final OutboxEventRepository outbox;
    private final JsonMapper jsonMapper;
    private final String transferTopic;

    public OutboxService(OutboxEventRepository outbox, JsonMapper jsonMapper,
                         @Value("${lockstep.kafka.transfer-topic}") String transferTopic) {
        this.outbox = outbox;
        this.jsonMapper = jsonMapper;
        this.transferTopic = transferTopic;
    }

    /**
     * MANDATORY: must run inside the transfer's transaction. That is the whole
     * point of the outbox; outside it, this method would be just as unsafe as
     * calling Kafka directly.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordTransferCompleted(Transfer transfer, Account from) {
        TransferCompletedEvent event = new TransferCompletedEvent(
                UUID.randomUUID(),
                TransferCompletedEvent.TYPE,
                transfer.getCreatedAt(),
                transfer.getId(),
                transfer.getFromAccountId(),
                from.getType().name(),
                transfer.getToAccountId(),
                transfer.getAmountCents(),
                transfer.getCurrency());

        outbox.save(new OutboxEvent(
                event.eventId(),
                "transfer",
                String.valueOf(transfer.getId()),
                event.eventType(),
                transferTopic,
                // Key = the paying account. Kafka keeps messages with the same key in one
                // partition, in order, so the fraud service sees each account's transfers
                // in the order they happened, handled by a single consumer at a time.
                String.valueOf(transfer.getFromAccountId()),
                jsonMapper.writeValueAsString(event),
                MDC.get(CorrelationIdFilter.MDC_KEY)));
    }
}
