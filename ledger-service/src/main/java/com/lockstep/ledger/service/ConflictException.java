package com.lockstep.ledger.service;

/** The request conflicts with the current state (e.g. username taken). Becomes HTTP 409. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
