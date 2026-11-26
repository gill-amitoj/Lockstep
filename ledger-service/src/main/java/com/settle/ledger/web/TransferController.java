package com.settle.ledger.web;

import com.settle.ledger.domain.Transfer;
import com.settle.ledger.service.TransferService;
import com.settle.ledger.web.dto.TransferRequest;
import com.settle.ledger.web.dto.TransferResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    /** Phase 2 adds the required Idempotency-Key header. */
    @PostMapping
    public ResponseEntity<TransferResponse> createTransfer(@RequestBody TransferRequest request) {
        Transfer transfer = transferService.transfer(
                request.fromAccountId(), request.toAccountId(), request.amountCents());
        return ResponseEntity
                .created(URI.create("/transfers/" + transfer.getId()))
                .body(TransferResponse.from(transfer));
    }

    @GetMapping("/{id}")
    public TransferResponse getTransfer(@PathVariable Long id) {
        return TransferResponse.from(transferService.getTransfer(id));
    }
}
