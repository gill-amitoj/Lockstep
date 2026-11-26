package com.lockstep.ledger.service;

import com.lockstep.ledger.domain.IdempotencyKey;
import com.lockstep.ledger.domain.Transfer;
import com.lockstep.ledger.repository.IdempotencyKeyRepository;
import com.lockstep.ledger.security.Caller;
import com.lockstep.ledger.web.dto.TransferRequest;
import com.lockstep.ledger.web.dto.TransferResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Wraps TransferService so that retrying POST /transfers with the same
 * Idempotency-Key never moves money twice.
 *
 * The whole method is ONE transaction: claiming the key, creating the
 * transfer, and storing the response commit together or not at all.
 *
 * What is (and is not) remembered:
 *  - Success (201): stored. Retries get the same 201 and body back.
 *  - Failure (400/404/422): NOT stored. The exception rolls back the whole
 *    transaction, including the key claim, so the client may retry with the
 *    same key (for example after funds arrive). Nothing was changed, so a
 *    retry cannot cause a double transfer.
 *
 * This service knows the response shape (TransferResponse) because what it
 * stores is literally the HTTP response body.
 */
@Service
public class IdempotentTransferService {

    static final int MAX_KEY_LENGTH = 255;

    private final IdempotencyKeyRepository keys;
    private final TransferService transferService;
    private final JsonMapper jsonMapper;

    public IdempotentTransferService(IdempotencyKeyRepository keys,
                                     TransferService transferService,
                                     JsonMapper jsonMapper) {
        this.keys = keys;
        this.transferService = transferService;
        this.jsonMapper = jsonMapper;
    }

    @Transactional
    public StoredResponse transfer(Caller caller, String idempotencyKey, TransferRequest request) {
        validateKey(idempotencyKey);
        String requestHash = hash(request);

        // 1. Claim the key. If another request with this key is in flight, this
        //    line blocks until that request's transaction ends.
        boolean claimed = keys.tryClaim(caller.username(), idempotencyKey, requestHash) == 1;

        if (!claimed) {
            // 2a. Someone already completed a request with this key.
            return replay(caller, idempotencyKey, requestHash);
        }

        // 2b. We own the key: do the real work. TransferService.transfer is also
        //     @Transactional; by default it JOINS this transaction rather than
        //     starting its own, so everything commits together.
        Transfer transfer = transferService.transfer(caller,
                request.fromAccountId(), request.toAccountId(), request.amountCents());

        String body = jsonMapper.writeValueAsString(TransferResponse.from(transfer));
        int status = HttpStatus.CREATED.value();
        keys.saveResponse(caller.username(), idempotencyKey, status, body, transfer.getId());

        return new StoredResponse(status, body, transfer.getId(), false);
    }

    private StoredResponse replay(Caller caller, String idempotencyKey, String requestHash) {
        IdempotencyKey existing = keys.findById(new IdempotencyKey.Id(caller.username(), idempotencyKey))
                .orElseThrow(() -> new IllegalStateException("Claimed key vanished: " + idempotencyKey));

        if (!existing.getRequestHash().equals(requestHash)) {
            throw new IdempotencyKeyReusedException();
        }
        if (existing.getResponseStatus() == null) {
            // Cannot happen: the response is written in the same transaction as the claim.
            throw new IllegalStateException("Idempotency key has no stored response: " + idempotencyKey);
        }
        return new StoredResponse(
                existing.getResponseStatus(), existing.getResponseBody(), existing.getTransferId(), true);
    }

    private static void validateKey(String key) {
        if (key == null || key.isBlank()) {
            throw new InvalidRequestException("Idempotency-Key header is required");
        }
        if (key.length() > MAX_KEY_LENGTH) {
            throw new InvalidRequestException("Idempotency-Key must be at most " + MAX_KEY_LENGTH + " characters");
        }
    }

    /**
     * SHA-256 of the request's meaning, not its raw bytes. {"a":1} and { "a" : 1 }
     * are the same request, so they hash the same. The field order in this string
     * is fixed, so the hash is stable.
     */
    static String hash(TransferRequest request) {
        String canonical = "fromAccountId=" + request.fromAccountId()
                + "&toAccountId=" + request.toAccountId()
                + "&amountCents=" + request.amountCents();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is guaranteed to exist in every JVM", e);
        }
    }
}
