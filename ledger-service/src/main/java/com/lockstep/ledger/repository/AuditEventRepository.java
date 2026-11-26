package com.lockstep.ledger.repository;

import com.lockstep.ledger.domain.AuditEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    /** Newest first. Pageable carries the LIMIT. */
    List<AuditEvent> findAllByOrderByIdDesc(Pageable pageable);

    List<AuditEvent> findByResourceTypeAndResourceIdOrderByIdAsc(String resourceType, String resourceId);

    List<AuditEvent> findByActorOrderByIdAsc(String actor);
}
