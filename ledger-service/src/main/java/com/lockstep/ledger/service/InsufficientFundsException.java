package com.lockstep.ledger.service;

/**
 * The request is well-formed but breaks a business rule: the source account
 * does not have enough money. Becomes HTTP 422.
 */
public class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException(Long accountId) {
        super("Account " + accountId + " has insufficient funds for this transfer");
    }
}
