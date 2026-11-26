package com.settle.ledger.service;

import com.settle.ledger.domain.Account;
import com.settle.ledger.domain.AccountType;
import com.settle.ledger.domain.Entry;
import com.settle.ledger.repository.AccountRepository;
import com.settle.ledger.repository.EntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final EntryRepository entries;

    // Constructor injection: dependencies are final and the class is easy to construct in tests.
    public AccountService(AccountRepository accounts, EntryRepository entries) {
        this.accounts = accounts;
        this.entries = entries;
    }

    @Transactional
    public Account openAccount(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) {
            throw new InvalidRequestException("ownerId is required");
        }
        return accounts.save(new Account(ownerId, AccountType.CUSTOMER));
    }

    @Transactional(readOnly = true)
    public Account getAccount(Long accountId) {
        return accounts.findById(accountId)
                .orElseThrow(() -> new NotFoundException("Account " + accountId + " not found"));
    }

    /** The account's statement: every entry, oldest first. */
    @Transactional(readOnly = true)
    public List<Entry> getEntries(Long accountId) {
        getAccount(accountId); // 404 if the account does not exist, rather than an empty list
        return entries.findByAccountIdOrderByIdAsc(accountId);
    }

    @Transactional(readOnly = true)
    public Account getSystemAccount() {
        return accounts.findFirstByType(AccountType.SYSTEM)
                .orElseThrow(() -> new IllegalStateException("SYSTEM account missing; check migrations"));
    }
}
