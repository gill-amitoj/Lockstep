package com.lockstep.ledger.service;

import com.lockstep.ledger.IntegrationTest;
import com.lockstep.ledger.domain.Account;
import com.lockstep.ledger.domain.IdempotencyKey;
import com.lockstep.ledger.repository.IdempotencyKeyRepository;
import com.lockstep.ledger.web.dto.TransferRequest;
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

        StoredResponse response = idempotentTransferService.transfer(ownerOf(alice), key, request);

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.replayed()).isFalse();
        assertThat(response.body()).contains("\"amountCents\":1000");

        IdempotencyKey stored = keyRepository.findById(new IdempotencyKey.Id(alice.getOwnerId(), key)).orElseThrow();
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

        StoredResponse first = idempotentTransferService.transfer(ownerOf(alice), key, request);
        StoredResponse retry = idempotentTransferService.transfer(ownerOf(alice), key, request);

        assertThat(retry.replayed()).isTrue();
        assertThat(retry.status()).isEqualTo(first.status());
        assertThat(retry.body()).isEqualTo(first.body());
        assertThat(retry.transferId()).isEqualTo(first.transferId());

        assertThat(balanceOf(alice.getId())).isEqualTo(9_000);
        assertThat(balanceOf(bob.getId())).isEqualTo(1_000);
        assertThat(entriesOf(bob.getId())).hasSize(1);
    }

    @Test
    void sameKeyWithDifferentBodyIsRejected() {
        Account alice = openFundedAccount(10_000);
        Account bob = openFundedAccount(0);
        String key = newKey();
        idempotentTransferService.transfer(ownerOf(alice), key, new TransferRequest(alice.getId(), bob.getId(), 1_000L));

        assertThatThrownBy(() -> idempotentTransferService.transfer(ownerOf(alice), key, new TransferRequest(alice.getId(), bob.getId(), 2_000L)))
                .isInstanceOf(IdempotencyKeyReusedException.class);

        assertThat(balanceOf(alice.getId())).isEqualTo(9_000);
    }

    @Test
    void failedTransferDoesNotUseUpTheKey() {
        Account alice = openFundedAccount(500);
        Account bob = openFundedAccount(0);
        String key = newKey();
        TransferRequest request = new TransferRequest(alice.getId(), bob.getId(), 1_000L);

        assertThatThrownBy(() -> idempotentTransferService.transfer(ownerOf(alice), key, request))
                .isInstanceOf(InsufficientFundsException.class);
        assertThat(keyRepository.findById(new IdempotencyKey.Id(alice.getOwnerId(), key))).isEmpty(); // rolled back together with the transfer

        // Money arrives, the client retries with the SAME key, and it now succeeds.
        transferService.transfer(ADMIN, accountService.getSystemAccount().getId(), alice.getId(), 500L);
        StoredResponse retry = idempotentTransferService.transfer(ownerOf(alice), key, request);

        assertThat(retry.replayed()).isFalse();
        assertThat(balanceOf(bob.getId())).isEqualTo(1_000);
    }

    @Test
    void missingBlankOrOverlongKeyIsRejected() {
        Account alice = openFundedAccount(1_000);
        Account bob = openFundedAccount(0);
        TransferRequest request = new TransferRequest(alice.getId(), bob.getId(), 100L);
        String tooLong = "k".repeat(IdempotentTransferService.MAX_KEY_LENGTH + 1);

        for (String badKey : new String[] {null, "", "   ", tooLong}) {
            assertThatThrownBy(() -> idempotentTransferService.transfer(ownerOf(alice), badKey, request))
                    .isInstanceOf(InvalidRequestException.class)
                    .hasMessageContaining("Idempotency-Key");
        }
        assertThat(balanceOf(alice.getId())).isEqualTo(1_000);
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
                    return idempotentTransferService.transfer(ownerOf(alice), key, request);
                }));
            }
            startGate.countDown();

            List<StoredResponse> responses = new ArrayList<>();
            for (Future<StoredResponse> future : futures) {
                responses.add(future.get(30, TimeUnit.SECONDS)); // rethrows if any thread failed
            }

            assertThat(responses).filteredOn(r -> !r.replayed()).hasSize(1);
            assertThat(responses).extracting(StoredResponse::body).containsOnly(responses.getFirst().body());
            assertThat(entriesOf(bob.getId())).hasSize(1);
            assertThat(balanceOf(alice.getId())).isEqualTo(99_000);
            assertThat(balanceOf(bob.getId())).isEqualTo(1_000);
        } finally {
            pool.shutdownNow();
        }
    }

    /** Keys are scoped per user (Phase 4): two customers choosing the same key don't collide. */
    @Test
    void sameKeyFromDifferentUsersAreIndependentRequests() {
        Account alice = openFundedAccount(1_000);
        Account bob = openFundedAccount(1_000);
        Account carol = openFundedAccount(0);
        String sharedKey = newKey();

        StoredResponse fromAlice = idempotentTransferService.transfer(
                ownerOf(alice), sharedKey, new TransferRequest(alice.getId(), carol.getId(), 100L));
        StoredResponse fromBob = idempotentTransferService.transfer(
                ownerOf(bob), sharedKey, new TransferRequest(bob.getId(), carol.getId(), 200L));

        assertThat(fromBob.replayed()).isFalse(); // not mistaken for Alice's request
        assertThat(fromBob.transferId()).isNotEqualTo(fromAlice.transferId());
        assertThat(balanceOf(carol.getId())).isEqualTo(300);
    }

    /** The guarantee rests on the database, not on Java: a duplicate key cannot be inserted. */
    @Test
    void databaseRejectsDuplicateKey() {
        String key = newKey();
        String insert = "INSERT INTO idempotency_keys (client_id, idempotency_key, request_hash, created_at)"
                + " VALUES ('someone', ?, ?, now())";
        jdbc.update(insert, key, "a".repeat(64));

        assertThatThrownBy(() -> jdbc.update(insert, key, "b".repeat(64)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }
}
