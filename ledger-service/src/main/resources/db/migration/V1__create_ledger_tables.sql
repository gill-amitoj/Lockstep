-- V1: core double-entry ledger.
--
-- Money is always BIGINT cents (minor units). Never floating point.
-- Sign convention for entries, from the account holder's point of view:
--   negative amount = debit  (money leaves the account)
--   positive amount = credit (money arrives in the account)
-- With signed amounts, the two core invariants are plain SQL:
--   accounts.balance_cents = SUM(entries.amount_cents) for that account
--   SUM(entries.amount_cents) for any one transfer = 0

CREATE TABLE accounts (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner_id      VARCHAR(100) NOT NULL,
    type          VARCHAR(20)  NOT NULL,
    currency      VARCHAR(3)   NOT NULL,
    balance_cents BIGINT       NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,

    CONSTRAINT accounts_type_valid     CHECK (type IN ('CUSTOMER', 'SYSTEM')),
    CONSTRAINT accounts_currency_cad   CHECK (currency = 'CAD'),
    -- Customer accounts can never be overdrawn, even if the Java check has a bug.
    -- The SYSTEM funding account is the one account allowed to go negative.
    CONSTRAINT accounts_customer_non_negative CHECK (type = 'SYSTEM' OR balance_cents >= 0)
);

-- At most one SYSTEM account (a partial unique index: only rows with type = 'SYSTEM' are indexed).
CREATE UNIQUE INDEX accounts_one_system_account ON accounts (type) WHERE type = 'SYSTEM';
CREATE INDEX accounts_owner_id_idx ON accounts (owner_id);

CREATE TABLE transfers (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    from_account_id BIGINT      NOT NULL REFERENCES accounts (id),
    to_account_id   BIGINT      NOT NULL REFERENCES accounts (id),
    amount_cents    BIGINT      NOT NULL,
    currency        VARCHAR(3)  NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,

    CONSTRAINT transfers_amount_positive CHECK (amount_cents > 0),
    CONSTRAINT transfers_distinct_accounts CHECK (from_account_id <> to_account_id),
    CONSTRAINT transfers_currency_cad CHECK (currency = 'CAD')
);

CREATE TABLE entries (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id  BIGINT      NOT NULL REFERENCES transfers (id),
    account_id   BIGINT      NOT NULL REFERENCES accounts (id),
    amount_cents BIGINT      NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,

    CONSTRAINT entries_amount_non_zero CHECK (amount_cents <> 0)
);

CREATE INDEX entries_account_id_idx  ON entries (account_id);
CREATE INDEX entries_transfer_id_idx ON entries (transfer_id);

-- Append-only: history is never edited. A mistake is fixed with a new,
-- reversing transfer, not by changing old rows. The database itself rejects
-- UPDATE and DELETE so that no code path (or person with SQL access) can bypass it.
-- Phase 4 reuses this function for the audit log.
CREATE FUNCTION reject_modification() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'table % is append-only: % is not allowed', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER entries_append_only
    BEFORE UPDATE OR DELETE ON entries
    FOR EACH ROW EXECUTE FUNCTION reject_modification();

CREATE TRIGGER transfers_append_only
    BEFORE UPDATE OR DELETE ON transfers
    FOR EACH ROW EXECUTE FUNCTION reject_modification();

-- The bank's funding account. Deposits into customer accounts are ordinary
-- transfers FROM this account, so every cent in the system has a matching
-- entry and the sum of all balances is always exactly 0.
-- (Its balance is negative: minus the total money issued to customers.)
INSERT INTO accounts (owner_id, type, currency, balance_cents, created_at)
VALUES ('system', 'SYSTEM', 'CAD', 0, now());
