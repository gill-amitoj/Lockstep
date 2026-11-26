package com.lockstep.ledger.service;

import com.lockstep.ledger.IntegrationTest;
import com.lockstep.ledger.domain.Account;
import com.lockstep.ledger.repository.EntryRepository;
import com.lockstep.ledger.security.Caller;
import com.lockstep.ledger.web.dto.TransferRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Many transfers at once between a few accounts, so the same rows are fought
 * over constantly. Without row locking this test fails with lost updates; with
 * locking in an inconsistent order it fails with deadlocks.
 *
 * Concurrency is real but bounded: the threads below submit work at the same
 * moment, and at most as many transactions run in parallel as the connection
 * pool allows (HikariCP default: 10). The rest wait for a connection.
 */
class ConcurrentTransferTest extends IntegrationTest {

    private static final int THREADS = 32;

    @Autowired
    private IdempotentTransferService idempotentTransferService;

    @Autowired
    private EntryRepository entryRepository;

    @Autowired
    private JdbcTemplate jdbc;

    /** Outcome of one attempted transfer. */
    private enum Outcome { SUCCEEDED, INSUFFICIENT_FUNDS }

    @Test
    void manyConcurrentTransfersKeepMoneyConservedAndBalancesNonNegative() throws Exception {
        int accountCount = 5;
        int transferCount = 400;
        long startingBalance = 10_000;

        List<Long> accountIds = new ArrayList<>();
        for (int i = 0; i < accountCount; i++) {
            accountIds.add(openFundedAccount(startingBalance).getId());
        }
        long totalBefore = startingBalance * accountCount;

        // Fixed seed: the same "random" transfers every run, so a failure can be reproduced.
        Random random = new Random(42);
        List<TransferRequest> requests = new ArrayList<>();
        for (int i = 0; i < transferCount; i++) {
            int from = random.nextInt(accountCount);
            int to = (from + 1 + random.nextInt(accountCount - 1)) % accountCount; // always != from
            // Up to 30% of a starting balance, so some transfers hit insufficient funds.
            long amount = 1 + random.nextInt(3_000);
            requests.add(new TransferRequest(accountIds.get(from), accountIds.get(to), amount));
        }

        List<Outcome> outcomes = runConcurrently(requests);

        long succeeded = outcomes.stream().filter(o -> o == Outcome.SUCCEEDED).count();
        System.out.printf("Concurrent transfers: %d attempted, %d succeeded, %d rejected for insufficient funds%n",
                outcomes.size(), succeeded, outcomes.size() - succeeded);

        assertThat(outcomes).hasSize(transferCount); // every transfer either succeeded or was cleanly rejected
        assertThat(succeeded).isPositive();
        assertLedgerConsistent(accountIds, totalBefore, succeeded);
    }

    /**
     * The classic deadlock: T1 moves A->B while T2 moves B->A. If each locked
     * its own "from" account first, T1 would hold A waiting for B, and T2 hold B
     * waiting for A. Locking in id order makes both lock A first, so one simply waits.
     */
    @Test
    void opposingTransfersBetweenTwoAccountsDoNotDeadlock() throws Exception {
        Account a = openFundedAccount(100_000);
        Account b = openFundedAccount(100_000);

        List<TransferRequest> requests = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            requests.add(i % 2 == 0
                    ? new TransferRequest(a.getId(), b.getId(), 10L)
                    : new TransferRequest(b.getId(), a.getId(), 10L));
        }

        List<Outcome> outcomes = runConcurrently(requests);

        assertThat(outcomes).containsOnly(Outcome.SUCCEEDED).hasSize(200);
        // 100 each way of the same amount: both end where they started.
        assertThat(balanceOf(a.getId())).isEqualTo(100_000);
        assertThat(balanceOf(b.getId())).isEqualTo(100_000);
        assertLedgerConsistent(List.of(a.getId(), b.getId()), 200_000, 200);
    }

    /**
     * The classic lost update: T1 and T2 both read hot = 100_000, T1 writes
     * 99_990, T2 also writes 99_990, and 10 cents vanish from the balance while
     * both entries exist. All transfers leave the SAME account, so they can't
     * deadlock; a wrong balance is the only possible failure.
     */
    @Test
    void manyTransfersOutOfOneAccountLoseNoUpdates() throws Exception {
        Account hot = openFundedAccount(100_000);
        List<Long> receivers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            receivers.add(openFundedAccount(0).getId());
        }

        List<TransferRequest> requests = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            requests.add(new TransferRequest(hot.getId(), receivers.get(i % receivers.size()), 10L));
        }

        List<Outcome> outcomes = runConcurrently(requests);

        assertThat(outcomes).containsOnly(Outcome.SUCCEEDED).hasSize(200);
        assertThat(balanceOf(hot.getId())).isEqualTo(100_000 - 200 * 10);
        List<Long> all = new ArrayList<>(receivers);
        all.add(hot.getId());
        assertLedgerConsistent(all, 100_000, 200);
    }

    /**
     * Fires every request through the real POST /transfers code path (with a fresh
     * idempotency key each), all released at the same instant by a latch.
     * Any exception other than InsufficientFunds (lost-update check violations,
     * deadlocks, ...) fails the test via Future.get().
     */
    private List<Outcome> runConcurrently(List<TransferRequest> requests) throws Exception {
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (TransferRequest request : requests) {
                // Each transfer is sent by the customer who owns the "from" account,
                // so the ownership check runs under load too.
                Caller sender = ownerOf(accountService.getAccount(ADMIN, request.fromAccountId()));
                Callable<Outcome> task = () -> {
                    startGate.await();
                    try {
                        idempotentTransferService.transfer(sender, UUID.randomUUID().toString(), request);
                        return Outcome.SUCCEEDED;
                    } catch (InsufficientFundsException e) {
                        return Outcome.INSUFFICIENT_FUNDS;
                    }
                };
                futures.add(pool.submit(task));
            }
            startGate.countDown();

            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private void assertLedgerConsistent(List<Long> accountIds, long expectedTotal, long expectedTransfers) {
        long total = 0;
        for (Long id : accountIds) {
            long balance = balanceOf(id);
            assertThat(balance).as("balance of account %d", id).isNotNegative();
            // No lost updates: the stored running balance matches the entries exactly.
            assertThat(balance).as("balance vs entries of account %d", id)
                    .isEqualTo(entryRepository.sumAmountByAccountId(id));
            total += balance;
        }
        assertThat(total).as("total money across the accounts").isEqualTo(expectedTotal);

        // Every transfer between these accounts produced exactly two entries summing to zero.
        String ids = String.join(",", accountIds.stream().map(String::valueOf).toList());
        Long transfers = jdbc.queryForObject(
                "SELECT count(*) FROM transfers WHERE from_account_id IN (" + ids + ") AND to_account_id IN (" + ids + ")",
                Long.class);
        assertThat(transfers).isEqualTo(expectedTransfers);
        Long unbalanced = jdbc.queryForObject("""
                SELECT count(*) FROM (
                    SELECT e.transfer_id FROM entries e JOIN transfers t ON t.id = e.transfer_id
                     WHERE t.from_account_id IN (%s)
                     GROUP BY e.transfer_id
                    HAVING count(*) <> 2 OR sum(e.amount_cents) <> 0
                ) bad
                """.formatted(ids), Long.class);
        assertThat(unbalanced).isZero();
    }
}
