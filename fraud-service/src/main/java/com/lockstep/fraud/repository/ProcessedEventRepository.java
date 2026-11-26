package com.lockstep.fraud.repository;

import com.lockstep.fraud.domain.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {

    /**
     * Returns 1 the first time an event id is seen, 0 for every repeat. Like the
     * ledger's idempotency keys, the PRIMARY KEY does the work: if two consumers
     * raced on the same event, the second waits for the first to commit and then gets 0.
     */
    @Modifying
    @Query(value = """
            INSERT INTO processed_events (event_id, processed_at) VALUES (:eventId, now())
            ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int markProcessed(@Param("eventId") UUID eventId);
}
