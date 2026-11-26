package com.lockstep.ledger.repository;

import com.lockstep.ledger.domain.Account;
import com.lockstep.ledger.domain.AccountType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    /** Spring Data derives the SQL from the method name: WHERE type = ? LIMIT 1. */
    Optional<Account> findFirstByType(AccountType type);

    /**
     * Loads an account AND locks its row until the current transaction ends.
     * PESSIMISTIC_WRITE makes Hibernate add a row lock to the SELECT. On
     * PostgreSQL, Hibernate 7 emits the FOR UPDATE variant FOR NO KEY UPDATE:
     *
     *   select ... from accounts a where a.id = ? for no key update of a
     *
     * It conflicts with other FOR [NO KEY] UPDATE locks and with UPDATEs, which is
     * all we need. Unlike plain FOR UPDATE it does not block the FOR KEY SHARE
     * lock that foreign-key checks take, so inserting an entry that references a
     * locked account is not held up. (Verify with SPRING_JPA_SHOW_SQL=true.)
     *
     * Any other transaction that tries to lock (or update) the same row waits
     * here until we commit or roll back, and then reads the NEW balance. That
     * turns "read, compute, write" into one step nobody can interleave with.
     * Plain reads (findById, GET /accounts) are not blocked.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") Long id);
}
