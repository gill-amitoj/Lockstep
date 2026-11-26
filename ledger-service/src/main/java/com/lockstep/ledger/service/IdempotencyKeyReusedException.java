package com.lockstep.ledger.service;

/**
 * The client reused an Idempotency-Key for a request with a different body.
 * That is a client bug, not a retry, so we refuse it. Becomes HTTP 422.
 */
public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException() {
        super("This Idempotency-Key was already used with a different request body");
    }
}
