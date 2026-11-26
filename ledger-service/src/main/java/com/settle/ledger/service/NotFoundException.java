package com.settle.ledger.service;

/** A requested account or transfer does not exist. Becomes HTTP 404. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
