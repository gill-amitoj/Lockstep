package com.lockstep.ledger.service;

import com.lockstep.ledger.domain.Account;
import com.lockstep.ledger.domain.Entry;
import com.lockstep.ledger.domain.Transfer;
import com.lockstep.ledger.repository.AccountRepository;
import com.lockstep.ledger.repository.EntryRepository;
import com.lockstep.ledger.repository.TransferRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {

    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final EntryRepository entries;

    public TransferService(AccountRepository accounts, TransferRepository transfers, EntryRepository entries) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.entries = entries;
    }

    /**
     * Moves money between two accounts. Everything inside runs in ONE database
     * transaction: the transfer row, both entries and both balance updates are
     * committed together or not at all. If any check fails, the exception makes
     * Spring roll back, so a failed transfer leaves no trace.
     *
     * KNOWN GAP (fixed in Phase 3): the accounts are read without a row lock.
     * Two concurrent transfers from the same account can both read the old
     * balance and one update overwrites the other (a "lost update").
     */
    @Transactional
    public Transfer transfer(Long fromAccountId, Long toAccountId, Long amountCents) {
        // 1. Validate the request itself before touching the database.
        if (fromAccountId == null || toAccountId == null || amountCents == null) {
            throw new InvalidRequestException("fromAccountId, toAccountId and amountCents are required");
        }
        if (amountCents <= 0) {
            throw new InvalidRequestException("amountCents must be positive");
        }
        if (fromAccountId.equals(toAccountId)) {
            throw new InvalidRequestException("Cannot transfer to the same account");
        }

        // 2. Load both accounts (404 if either is missing).
        Account from = findAccount(fromAccountId);
        Account to = findAccount(toAccountId);

        // 3. Business rule: customers cannot overdraw.
        if (!from.canDebit(amountCents)) {
            throw new InsufficientFundsException(fromAccountId);
        }

        // 4. Write the transfer and its two balancing entries.
        Transfer transfer = transfers.save(new Transfer(fromAccountId, toAccountId, amountCents));
        entries.save(Entry.debit(transfer.getId(), fromAccountId, amountCents));
        entries.save(Entry.credit(transfer.getId(), toAccountId, amountCents));

        // 5. Update the running balances. No save() call is needed: `from` and `to`
        // are managed JPA entities, and Hibernate writes their changes (an UPDATE)
        // automatically when the transaction commits ("dirty checking").
        from.debit(amountCents);
        to.credit(amountCents);

        return transfer;
    }

    @Transactional(readOnly = true)
    public Transfer getTransfer(Long transferId) {
        return transfers.findById(transferId)
                .orElseThrow(() -> new NotFoundException("Transfer " + transferId + " not found"));
    }

    private Account findAccount(Long accountId) {
        return accounts.findById(accountId)
                .orElseThrow(() -> new NotFoundException("Account " + accountId + " not found"));
    }
}
