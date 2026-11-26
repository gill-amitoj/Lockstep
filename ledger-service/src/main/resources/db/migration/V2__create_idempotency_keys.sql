-- V2: idempotency keys for POST /transfers.
--
-- A client sends a unique Idempotency-Key header with each logical request.
-- If the network drops the response and the client retries with the same key,
-- we return the stored response instead of moving the money a second time.
--
-- The PRIMARY KEY on idempotency_key is what makes this safe under concurrency:
-- two requests with the same key cannot both insert a row, so only one of them
-- can go on to create a transfer.
--
-- Phase 4 scopes keys per user (so two customers can't collide on a key).

CREATE TABLE idempotency_keys (
    idempotency_key VARCHAR(255) PRIMARY KEY,
    -- SHA-256 (hex) of the request's content. Same key + different hash = client bug, 422.
    request_hash    VARCHAR(64)  NOT NULL,
    -- The response is written in the same transaction as the transfer, so a
    -- committed row always has these filled in.
    response_status INTEGER,
    -- TEXT, not JSONB: JSONB reorders keys and strips whitespace, and a replay
    -- should return byte-for-byte the original response.
    response_body   TEXT,
    transfer_id     BIGINT       REFERENCES transfers (id),
    created_at      TIMESTAMPTZ  NOT NULL
);
