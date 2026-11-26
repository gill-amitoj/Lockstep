package com.settle.ledger.domain;

public enum AccountType {
    /** A customer's account. Can never go below zero. */
    CUSTOMER,
    /** The bank's funding account. Deposits come from here, so it may go negative. */
    SYSTEM
}
