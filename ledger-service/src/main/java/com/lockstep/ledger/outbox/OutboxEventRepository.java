package com.lockstep.ledger.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * The next batch of unpublished events, oldest first, locked for this transaction.
     *
     * FOR UPDATE SKIP LOCKED: if two relay instances run at once, each locks
     * different rows and skips the ones the other has taken, instead of both
     * sending the same events (or one waiting for the other).
     */
    @Query(value = """
            SELECT * FROM outbox_events
             WHERE published_at IS NULL
             ORDER BY created_at
             LIMIT :batchSize
               FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockNextUnpublished(@Param("batchSize") int batchSize);

    List<OutboxEvent> findByAggregateTypeAndAggregateId(String aggregateType, String aggregateId);

    long countByPublishedAtIsNull();
}
