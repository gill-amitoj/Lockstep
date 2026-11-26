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
     * Concurrency: both account rows are locked (SELECT ... FOR UPDATE) before
     * the balance is checked, and the locks are held until commit. Without the
     * lock, two transfers could both read the same old balance and one write
     * would overwrite the other (a "lost update"). See ConcurrentTransferTest.
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

        // 2. Lock both account rows, ALWAYS the lower id first (404 if either is missing).
        //    If we locked `from` first instead, A->B and B->A running together could
        //    each hold one row and wait forever for the other: a deadlock. With one
        //    global order, both lock the lower id first, so one simply waits its turn.
        Long firstId = Math.min(fromAccountId, toAccountId);
        Long secondId = Math.max(fromAccountId, toAccountId);
        Account first = lockAccount(firstId);
        Account second = lockAccount(secondId);
        Account from = first.getId().equals(fromAccountId) ? first : second;
        Account to = (from == first) ? second : first;

        // 3. Business rule: customers cannot overdraw. Safe to check now: we hold
        //    the lock, so this balance cannot change until we commit.
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

    private Account lockAccount(Long accountId) {
        return accounts.findByIdForUpdate(accountId)
                .orElseThrow(() -> new NotFoundException("Account " + accountId + " not found"));
    }
}
