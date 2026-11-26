package com.settle.ledger.web.dto;

import com.settle.ledger.domain.Account;

import java.time.Instant;

/**
 * What the API returns for an account. A separate record (not the entity itself)
 * so the JSON shape is an explicit contract that does not change when the table does.
 */
public record AccountResponse(
        Long id,
        String ownerId,
        String type,
        String currency,
        long balanceCents,
        Instant createdAt) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getOwnerId(),
                account.getType().name(),
                account.getCurrency(),
                account.getBalanceCents(),
                account.getCreatedAt());
    }
}
