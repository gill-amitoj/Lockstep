package com.lockstep.ledger.web.dto;

/**
 * Boxed Long (not primitive long) so a missing JSON field arrives as null and is
 * rejected, instead of silently becoming 0.
 */
public record TransferRequest(Long fromAccountId, Long toAccountId, Long amountCents) {
}
