package com.lockstep.ledger.repository;

import com.lockstep.ledger.domain.Entry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface EntryRepository extends JpaRepository<Entry, Long> {

    List<Entry> findByAccountIdOrderByIdAsc(Long accountId);

    List<Entry> findByTransferIdOrderByIdAsc(Long transferId);

    /** The balance recomputed from scratch: the sum of every entry for the account. */
    @Query("select coalesce(sum(e.amountCents), 0) from Entry e where e.accountId = :accountId")
    long sumAmountByAccountId(@Param("accountId") Long accountId);
}
