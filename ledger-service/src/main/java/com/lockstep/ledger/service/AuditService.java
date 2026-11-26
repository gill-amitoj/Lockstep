package com.lockstep.ledger.service;

import com.lockstep.ledger.domain.AuditEvent;
import com.lockstep.ledger.logging.CorrelationIdFilter;
import com.lockstep.ledger.repository.AuditEventRepository;
import org.slf4j.MDC;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/**
 * Records who did what, and when.
 *
 * Propagation.MANDATORY: record() must be called inside an existing transaction
 * and joins it. The audit row then commits or rolls back together with the
 * action it describes: there is never a transfer without its audit record, nor
 * an audit record for a transfer that didn't happen. Calling it outside a
 * transaction is a bug, and Spring throws immediately.
 */
@Service
public class AuditService {

    public static final String SCHEDULER_ACTOR = "#scheduler";
    public static final String BOOTSTRAP_ACTOR = "#bootstrap";

    private final AuditEventRepository events;
    private final JsonMapper jsonMapper;

    public AuditService(AuditEventRepository events, JsonMapper jsonMapper) {
        this.events = events;
        this.jsonMapper = jsonMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String actor, String action, String resourceType, Object resourceId, Map<String, ?> details) {
        events.save(new AuditEvent(
                actor,
                action,
                resourceType,
                resourceId == null ? null : resourceId.toString(),
                details == null || details.isEmpty() ? null : jsonMapper.writeValueAsString(details),
                MDC.get(CorrelationIdFilter.MDC_KEY)));
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> recent(int limit) {
        return events.findAllByOrderByIdDesc(PageRequest.of(0, limit));
    }
}
