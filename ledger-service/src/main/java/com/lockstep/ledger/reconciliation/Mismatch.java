package com.lockstep.ledger.reconciliation;

/**
 * One problem found by reconciliation.
 *
 * @param kind          ACCOUNT_BALANCE, TRANSFER_UNBALANCED or LEDGER_NOT_ZERO
 * @param accountId     the account involved (ACCOUNT_BALANCE only)
 * @param transferId    the transfer involved (TRANSFER_UNBALANCED only)
 * @param expectedCents what the value should be, recomputed from entries
 * @param actualCents   what was actually found
 */
public record Mismatch(
        String kind,
        Long accountId,
        Long transferId,
        Long expectedCents,
        Long actualCents,
        String detail) {
}
