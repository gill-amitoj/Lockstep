package com.settle.ledger.web;

import com.settle.ledger.service.IdempotentTransferService;
import com.settle.ledger.service.StoredResponse;
import com.settle.ledger.service.TransferService;
import com.settle.ledger.web.dto.TransferRequest;
import com.settle.ledger.web.dto.TransferResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    /** Tells the client "this is the stored response from your earlier request". */
    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final TransferService transferService;
    private final IdempotentTransferService idempotentTransferService;

    public TransferController(TransferService transferService,
                              IdempotentTransferService idempotentTransferService) {
        this.transferService = transferService;
        this.idempotentTransferService = idempotentTransferService;
    }

    /**
     * The header is declared optional here on purpose: the service checks it, so a
     * missing key gets our own clear 400 problem message rather than Spring's generic one.
     *
     * The body is returned as the stored JSON string, so the first response and
     * every replay are byte-for-byte identical.
     */
    @PostMapping
    public ResponseEntity<String> createTransfer(
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody TransferRequest request) {
        StoredResponse response = idempotentTransferService.transfer(idempotencyKey, request);

        ResponseEntity.BodyBuilder builder = ResponseEntity
                .status(response.status())
                .contentType(MediaType.APPLICATION_JSON)
                .location(URI.create("/transfers/" + response.transferId()));
        if (response.replayed()) {
            builder.header(REPLAYED_HEADER, "true");
        }
        return builder.body(response.body());
    }

    @GetMapping("/{id}")
    public TransferResponse getTransfer(@PathVariable Long id) {
        return TransferResponse.from(transferService.getTransfer(id));
    }
}
