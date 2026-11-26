package com.settle.ledger.service;

import com.settle.ledger.IntegrationTest;
import com.settle.ledger.domain.Account;
import com.settle.ledger.domain.IdempotencyKey;
import com.settle.ledger.repository.IdempotencyKeyRepository;
import com.settle.ledger.web.dto.TransferRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotentTransferServiceTest extends IntegrationTest {

    @Autowired
    private IdempotentTransferService idempotentTransferService;

    @Autowired
    private IdempotencyKeyRepository keyRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void firstRequestCreatesTransferAndStoresResponse() {
        Account alice = openFundedAccount(10_000);
        Account bob = openFundedAccount(0);
        String key = newKey();
        TransferRequest request = new TransferRequest(alice.getId(), bob.getId(), 1_000L);

        StoredResponse response = idempotentTransferService.transfer(key, request);

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.replayed()).isFalse();
        assertThat(response.body()).contains("\"amountCents\":1000");

        IdempotencyKey stored = keyRepository.findById(key).orElseThrow();
        assertThat(stored.getRequestHash()).isEqualTo(IdempotentTransferService.hash(request)).hasSize(64);
        assertThat(stored.getResponseStatus()).isEqualTo(201);
        assertThat(stored.getResponseBody()).isEqualTo(response.body());
        assertThat(stored.getTransferId()).isEqualTo(response.transferId());
    }

    @Test
    void sameKeyAndSameBodyReturnsOriginalResponseWithoutMovingMoneyAgain() {
        Account alice = openFundedAccount(10_000);
        Account bob = openFundedAccount(0);
        String key = newKey();
        TransferRequest request = new TransferRequest(alice.getId(), bob.getId(), 1_000L);

        StoredResponse first = idempotentTransferService.transfer(key, request);
        StoredResponse retry = idempotentTransferService.transfer(key, request);

        assertThat(retry.replayed()).isTrue();
        assertThat(retry.status()).isEqualTo(first.status());
        assertThat(retry.body()).isEqualTo(first.body());
        assertThat(retry.transferId()).isEqualTo(first.transferId());

        assertThat(accountService.getAccount(alice.getId()).getBalanceCents()).isEqualTo(9_000);
        assertThat(accountService.getAccount(bob.getId()).getBalanceCents()).isEqualTo(1_000);
        assertThat(accountService.getEntries(bob.getId())).hasSize(1);
    }

    @Test
    void sameKeyWithDifferentBodyIsRejected() {
        Account alice = openFundedAccount(10_000);
        Account bob = openFundedAccount(0);
        String key = newKey();
        idempotentTransferService.transfer(key, new TransferRequest(alice.getId(), bob.getId(), 1_000L));

        assertThatThrownBy(() -> idempotentTransferService.transfer(
                key, new TransferRequest(alice.getId(), bob.getId(), 2_000L)))
                .isInstanceOf(IdempotencyKeyReusedException.class);

        assertThat(accountService.getAccount(alice.getId()).getBalanceCents()).isEqualTo(9_000);
    }

    @Test
    void failedTransferDoesNotUseUpTheKey() {
        Account alice = openFundedAccount(500);
        Account bob = openFundedAccount(0);
        String key = newKey();
        TransferRequest request = new TransferRequest(alice.getId(), bob.getId(), 1_000L);

        assertThatThrownBy(() -> idempotentTransferService.transfer(key, request))
                .isInstanceOf(InsufficientFundsException.class);
        assertThat(keyRepository.findById(key)).isEmpty(); // rolled back together with the transfer

        // Money arrives, the client retries with the SAME key, and it now succeeds.
        transferService.transfer(accountService.getSystemAccount().getId(), alice.getId(), 500L);
        StoredResponse retry = idempotentTransferService.transfer(key, request);

        assertThat(retry.replayed()).isFalse();
        assertThat(accountService.getAccount(bob.getId()).getBalanceCents()).isEqualTo(1_000);
    }

    @Test
    void missingBlankOrOverlongKeyIsRejected() {
        Account alice = openFundedAccount(1_000);
        Account bob = openFundedAccount(0);
        TransferRequest request = new TransferRequest(alice.getId(), bob.getId(), 100L);
        String tooLong = "k".repeat(IdempotentTransferService.MAX_KEY_LENGTH + 1);

        for (String badKey : new String[] {null, "", "   ", tooLong}) {
            assertThatThrownBy(() -> idempotentTransferService.transfer(badKey, request))
                    .isInstanceOf(InvalidRequestException.class)
                    .hasMessageContaining("Idempotency-Key");
        }
        assertThat(accountService.getAccount(alice.getId()).getBalanceCents()).isEqualTo(1_000);
    }

    /**
     * The core guarantee: many requests with the same key arriving at the same
     * moment create exactly one transfer. All threads wait on a latch and are
     * released together to make them collide as much as possible.
     */
    @Test
    void concurrentRequestsWithSameKeyCreateExactlyOneTransfer() throws Exception {
        Account alice = openFundedAccount(100_000);
        Account bob = openFundedAccount(0);
        String key = newKey();
        TransferRequest request = new TransferRequest(alice.getId(), bob.getId(), 1_000L);
        int threads = 20;

        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<StoredResponse>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    startGate.await();
                    return idempotentTransferService.transfer(key, request);
                }));
            }
            startGate.countDown();

            List<StoredResponse> responses = new ArrayList<>();
            for (Future<StoredResponse> future : futures) {
                responses.add(future.get(30, TimeUnit.SECONDS)); // rethrows if any thread failed
            }

            assertThat(responses).filteredOn(r -> !r.replayed()).hasSize(1);
            assertThat(responses).extracting(StoredResponse::body).containsOnly(responses.getFirst().body());
            assertThat(accountService.getEntries(bob.getId())).hasSize(1);
            assertThat(accountService.getAccount(alice.getId()).getBalanceCents()).isEqualTo(99_000);
            assertThat(accountService.getAccount(bob.getId()).getBalanceCents()).isEqualTo(1_000);
        } finally {
            pool.shutdownNow();
        }
    }

    /** The guarantee rests on the database, not on Java: a duplicate key cannot be inserted. */
    @Test
    void databaseRejectsDuplicateKey() {
        String key = newKey();
        String insert = "INSERT INTO idempotency_keys (idempotency_key, request_hash, created_at) VALUES (?, ?, now())";
        jdbc.update(insert, key, "a".repeat(64));

        assertThatThrownBy(() -> jdbc.update(insert, key, "b".repeat(64)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }
}
