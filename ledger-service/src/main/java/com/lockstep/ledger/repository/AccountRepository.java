package com.lockstep.ledger.repository;

import com.lockstep.ledger.domain.Account;
import com.lockstep.ledger.domain.AccountType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    /** Spring Data derives the SQL from the method name: WHERE type = ? LIMIT 1. */
    Optional<Account> findFirstByType(AccountType type);
}
