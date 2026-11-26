package com.lockstep.fraud.repository;

import com.lockstep.fraud.domain.TransferObservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface TransferObservationRepository extends JpaRepository<TransferObservation, Long> {

    /** How many earlier transfers the account made (excluding this one). */
    @Query("""
            select count(o) from TransferObservation o
             where o.accountId = :accountId and o.transferId <> :transferId and o.occurredAt <= :at
            """)
    long countEarlier(@Param("accountId") Long accountId, @Param("transferId") Long transferId,
                      @Param("at") Instant at);

    /** Total cents of those earlier transfers. With countEarlier, gives the average without floating point. */
    @Query("""
            select coalesce(sum(o.amountCents), 0) from TransferObservation o
             where o.accountId = :accountId and o.transferId <> :transferId and o.occurredAt <= :at
            """)
    long sumEarlier(@Param("accountId") Long accountId, @Param("transferId") Long transferId,
                    @Param("at") Instant at);

    /** Transfers from the account with occurredAt in (from, to], so the window includes the current transfer. */
    @Query("""
            select count(o) from TransferObservation o
             where o.accountId = :accountId and o.occurredAt > :from and o.occurredAt <= :to
            """)
    long countInWindow(@Param("accountId") Long accountId, @Param("from") Instant from, @Param("to") Instant to);
}
