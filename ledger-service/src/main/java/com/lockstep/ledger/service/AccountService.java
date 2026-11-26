package com.lockstep.ledger.service;

import com.lockstep.ledger.domain.Account;
import com.lockstep.ledger.domain.AccountType;
import com.lockstep.ledger.domain.Entry;
import com.lockstep.ledger.repository.AccountRepository;
import com.lockstep.ledger.repository.EntryRepository;
import com.lockstep.ledger.security.Caller;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final EntryRepository entries;
    private final AuditService audit;

    // Constructor injection: dependencies are final and the class is easy to construct in tests.
    public AccountService(AccountRepository accounts, EntryRepository entries, AuditService audit) {
        this.accounts = accounts;
        this.entries = entries;
        this.audit = audit;
    }

    /** Opens a CUSTOMER account owned by the caller. The owner always comes from the token, never the request body. */
    @Transactional
    public Account openAccount(Caller caller) {
        Account account = accounts.save(new Account(caller.username(), AccountType.CUSTOMER));
        audit.record(caller.username(), "ACCOUNT_OPENED", "account", account.getId(), Map.of());
        return account;
    }

    /**
     * 404 both when the account doesn't exist and when it isn't the caller's.
     * A 403 would confirm "this account id exists, it's just not yours", which
     * lets someone map out real account numbers.
     */
    @Transactional(readOnly = true)
    public Account getAccount(Caller caller, Long accountId) {
        Account account = accounts.findById(accountId).orElseThrow(() -> notFound(accountId));
        if (!caller.canAccess(account.getOwnerId())) {
            throw notFound(accountId);
        }
        return account;
    }

    /** Customers see their own accounts; admins see all of them. */
    @Transactional(readOnly = true)
    public List<Account> listAccounts(Caller caller) {
        return caller.isAdmin()
                ? accounts.findAll(Sort.by("id"))
                : accounts.findByOwnerIdOrderByIdAsc(caller.username());
    }

    /** The account's statement: every entry, oldest first. */
    @Transactional(readOnly = true)
    public List<Entry> getEntries(Caller caller, Long accountId) {
        getAccount(caller, accountId); // 404 unless it exists and the caller may see it
        return entries.findByAccountIdOrderByIdAsc(accountId);
    }

    @Transactional(readOnly = true)
    public Account getSystemAccount() {
        return accounts.findFirstByType(AccountType.SYSTEM)
                .orElseThrow(() -> new IllegalStateException("SYSTEM account missing; check migrations"));
    }

    private static NotFoundException notFound(Long accountId) {
        return new NotFoundException("Account " + accountId + " not found");
    }
}
