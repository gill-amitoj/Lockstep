package com.settle.ledger.web.dto;

import com.settle.ledger.domain.Entry;

import java.time.Instant;

public record EntryResponse(
        Long id,
        Long transferId,
        Long accountId,
        long amountCents,
        Instant createdAt) {

    public static EntryResponse from(Entry entry) {
        return new EntryResponse(
                entry.getId(),
                entry.getTransferId(),
                entry.getAccountId(),
                entry.getAmountCents(),
                entry.getCreatedAt());
    }
}
