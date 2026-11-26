package com.lockstep.ledger.service;

/**
 * Wrong username or password. Becomes HTTP 401.
 * One message for both cases, so a caller can't probe which usernames exist.
 */
public class AuthenticationFailedException extends RuntimeException {

    public AuthenticationFailedException() {
        super("Invalid username or password");
    }
}
