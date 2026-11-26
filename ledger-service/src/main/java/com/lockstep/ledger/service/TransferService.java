package com.lockstep.ledger.service;

import com.lockstep.ledger.domain.Account;
import com.lockstep.ledger.domain.Entry;
import com.lockstep.ledger.domain.Transfer;
import com.lockstep.ledger.outbox.OutboxService;
import com.lockstep.ledger.repository.AccountRepository;
import com.lockstep.ledger.repository.EntryRepository;
import com.lockstep.ledger.repository.TransferRepository;
import com.lockstep.ledger.security.Caller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final EntryRepository entries;
    private final AuditService audit;
    private final OutboxService outbox;

    public TransferService(AccountRepository accounts, TransferRepository transfers,
                           EntryRepository entries, AuditService audit, OutboxService outbox) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.entries = entries;
        this.audit = audit;
        this.outbox = outbox;
    }

    /**
     * Moves money between two accounts. Everything inside runs in ONE database
     * transaction: the transfer row, both entries, both balance updates, the
     * audit record and the outbox event are committed together or not at all.
     * If any check fails, the exception makes Spring roll back, so a failed
     * transfer leaves no trace.
     *
     * Concurrency: both account rows are locked (SELECT ... FOR UPDATE) before
     * the balance is checked, and the locks are held until commit. Without the
     * lock, two transfers could both read the same old balance and one write
     * would overwrite the other (a "lost update"). See ConcurrentTransferTest.
     *
     * Authorization: a customer may only send FROM an account they own (to any
     * existing account). An admin may send from any account, including SYSTEM.
     */
    @Transactional
    public Transfer transfer(Caller caller, Long fromAccountId, Long toAccountId, Long amountCents) {
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

        // 2. Ownership check BEFORE locking, so a customer can't hold locks on
        //    accounts they don't own. It reads only the owner column (a String),
        //    not the Account entity: if the entity were loaded here without a lock,
        //    the locking query below would hand back this same cached object with
        //    its possibly stale balance, quietly undoing the Phase 3 fix.
        //    Not your account -> 404, the same as "doesn't exist", so customers
        //    can't discover which account ids are real.
        String ownerId = accounts.findOwnerIdById(fromAccountId).orElseThrow(() -> accountNotFound(fromAccountId));
        if (!caller.canAccess(ownerId)) {
            throw accountNotFound(fromAccountId);
        }

        // 3. Lock both account rows, ALWAYS the lower id first (404 if either is missing).
        //    If we locked `from` first instead, A->B and B->A running together could
        //    each hold one row and wait forever for the other: a deadlock. With one
        //    global order, both lock the lower id first, so one simply waits its turn.
        Long firstId = Math.min(fromAccountId, toAccountId);
        Long secondId = Math.max(fromAccountId, toAccountId);
        Account first = lockAccount(firstId);
        Account second = lockAccount(secondId);
        Account from = first.getId().equals(fromAccountId) ? first : second;
        Account to = (from == first) ? second : first;

        // 4. Business rule: customers cannot overdraw. Safe to check now: we hold
        //    the lock, so this balance cannot change until we commit.
        if (!from.canDebit(amountCents)) {
            log.atWarn().addKeyValue("fromAccountId", fromAccountId).addKeyValue("amountCents", amountCents)
                    .log("Transfer rejected: insufficient funds");
            throw new InsufficientFundsException(fromAccountId);
        }

        // 5. Write the transfer and its two balancing entries.
        Transfer transfer = transfers.save(new Transfer(fromAccountId, toAccountId, amountCents));
        entries.save(Entry.debit(transfer.getId(), fromAccountId, amountCents));
        entries.save(Entry.credit(transfer.getId(), toAccountId, amountCents));

        // 6. Update the running balances. No save() call is needed: `from` and `to`
        // are managed JPA entities, and Hibernate writes their changes (an UPDATE)
        // automatically when the transaction commits ("dirty checking").
        from.debit(amountCents);
        to.credit(amountCents);

        audit.record(caller.username(), "TRANSFER_CREATED", "transfer", transfer.getId(), Map.of(
                "fromAccountId", fromAccountId,
                "toAccountId", toAccountId,
                "amountCents", amountCents));
        // 7. Announce it to other services via the outbox: same transaction, so the
        //    event exists if and only if the transfer does (see V6__outbox.sql).
        outbox.recordTransferCompleted(transfer, from);
        log.atInfo().addKeyValue("transferId", transfer.getId()).addKeyValue("fromAccountId", fromAccountId)
                .addKeyValue("toAccountId", toAccountId).addKeyValue("amountCents", amountCents)
                .log("Transfer created");
        return transfer;
    }

    /** A customer may see a transfer if they own either side of it. */
    @Transactional(readOnly = true)
    public Transfer getTransfer(Caller caller, Long transferId) {
        Transfer transfer = transfers.findById(transferId).orElseThrow(() -> transferNotFound(transferId));
        if (caller.isAdmin()
                || caller.canAccess(ownerOf(transfer.getFromAccountId()))
                || caller.canAccess(ownerOf(transfer.getToAccountId()))) {
            return transfer;
        }
        throw transferNotFound(transferId);
    }

    private String ownerOf(Long accountId) {
        return accounts.findOwnerIdById(accountId).orElseThrow(() -> accountNotFound(accountId));
    }

    private Account lockAccount(Long accountId) {
        return accounts.findByIdForUpdate(accountId).orElseThrow(() -> accountNotFound(accountId));
    }

    private static NotFoundException accountNotFound(Long accountId) {
        return new NotFoundException("Account " + accountId + " not found");
    }

    private static NotFoundException transferNotFound(Long transferId) {
        return new NotFoundException("Transfer " + transferId + " not found");
    }
}
