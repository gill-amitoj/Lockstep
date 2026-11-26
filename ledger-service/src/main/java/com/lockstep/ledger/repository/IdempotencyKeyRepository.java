package com.lockstep.ledger.repository;

import com.lockstep.ledger.domain.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, IdempotencyKey.Id> {

    /**
     * Tries to claim a key for a client. Returns 1 if this transaction now owns
     * it, 0 if another request already has it.
     *
     * Native SQL because JPA has no portable "ON CONFLICT DO NOTHING". If another
     * transaction inserted the same (client, key) but has not committed yet,
     * PostgreSQL makes this statement WAIT until that transaction commits (then
     * we get 0) or rolls back (then our insert succeeds and we get 1). That
     * waiting is what serializes concurrent requests with the same key.
     */
    @Modifying
    @Query(value = """
            INSERT INTO idempotency_keys (client_id, idempotency_key, request_hash, created_at)
            VALUES (:clientId, :key, :requestHash, now())
            ON CONFLICT (client_id, idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int tryClaim(@Param("clientId") String clientId, @Param("key") String key,
                 @Param("requestHash") String requestHash);

    /** Stores the response for a key this transaction claimed. */
    @Modifying
    @Query(value = """
            UPDATE idempotency_keys
               SET response_status = :status, response_body = :body, transfer_id = :transferId
             WHERE client_id = :clientId AND idempotency_key = :key
            """, nativeQuery = true)
    int saveResponse(@Param("clientId") String clientId,
                     @Param("key") String key,
                     @Param("status") int status,
                     @Param("body") String body,
                     @Param("transferId") Long transferId);
}
