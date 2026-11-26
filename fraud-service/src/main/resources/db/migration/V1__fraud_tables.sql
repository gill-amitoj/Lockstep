-- V1: fraud service schema (its own database, lockstep_fraud).

-- "Inbox" for deduplication, the consumer's half of the outbox pattern. The
-- ledger delivers each event AT LEAST once, so the same event can arrive twice.
-- Inserting its id here in the same transaction as the fraud checks means a
-- second copy finds the id already present and is skipped.
CREATE TABLE processed_events (
    event_id     UUID        PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL
);

-- The fraud service's own copy of the customer transfers it has seen, which
-- is the history the rules look back over. It never asks the ledger: it must
-- keep working while the ledger is down.
CREATE TABLE transfer_observations (
    transfer_id  BIGINT      PRIMARY KEY,
    event_id     UUID        NOT NULL UNIQUE,
    account_id   BIGINT      NOT NULL,   -- the paying account
    amount_cents BIGINT      NOT NULL,
    occurred_at  TIMESTAMPTZ NOT NULL    -- when the transfer happened (event time), not when we saw it
);

-- Both rules ask "this account's transfers around this time".
CREATE INDEX transfer_observations_account_time_idx ON transfer_observations (account_id, occurred_at);

CREATE TABLE fraud_flags (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id     UUID        NOT NULL,
    transfer_id  BIGINT      NOT NULL,
    account_id   BIGINT      NOT NULL,
    rule         VARCHAR(50) NOT NULL,
    reason       TEXT        NOT NULL,
    amount_cents BIGINT      NOT NULL,
    occurred_at  TIMESTAMPTZ NOT NULL,   -- when the flagged transfer happened
    flagged_at   TIMESTAMPTZ NOT NULL,

    -- Backstop for deduplication: one flag per rule per transfer, whatever happens.
    CONSTRAINT fraud_flags_one_per_rule UNIQUE (transfer_id, rule)
);

CREATE INDEX fraud_flags_account_idx ON fraud_flags (account_id);
