package com.settle.ledger.web.dto;

import com.settle.ledger.domain.Transfer;

import java.time.Instant;

public record TransferResponse(
        Long id,
        Long fromAccountId,
        Long toAccountId,
        long amountCents,
        String currency,
        Instant createdAt) {

    public static TransferResponse from(Transfer transfer) {
        return new TransferResponse(
                transfer.getId(),
                transfer.getFromAccountId(),
                transfer.getToAccountId(),
                transfer.getAmountCents(),
                transfer.getCurrency(),
                transfer.getCreatedAt());
    }
}
