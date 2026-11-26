package com.lockstep.ledger.service;

/** The request itself is invalid (missing field, non-positive amount, same account). Becomes HTTP 400. */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
