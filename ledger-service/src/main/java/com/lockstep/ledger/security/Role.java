package com.lockstep.ledger.security;

public enum Role {
    /** Can open accounts and use only the accounts they own. */
    CUSTOMER,
    /** Can see every account, move money from any account (including SYSTEM), and use /admin endpoints. */
    ADMIN
}
