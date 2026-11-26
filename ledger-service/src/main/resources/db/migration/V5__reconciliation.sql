-- V5: results of the reconciliation job.
--
-- Each run recomputes every balance from the entries and checks every
-- transfer's entries. The run is recorded even when nothing is wrong, so
-- there is proof the check happened.

CREATE TABLE reconciliation_runs (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    started_at        TIMESTAMPTZ NOT NULL,
    finished_at       TIMESTAMPTZ NOT NULL,
    accounts_checked  BIGINT      NOT NULL,
    transfers_checked BIGINT      NOT NULL,
    mismatch_count    INTEGER     NOT NULL,
    status            VARCHAR(20) NOT NULL,

    CONSTRAINT reconciliation_runs_status_valid CHECK (status IN ('OK', 'MISMATCH'))
);

CREATE TABLE reconciliation_mismatches (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id         BIGINT      NOT NULL REFERENCES reconciliation_runs (id),
    kind           VARCHAR(30) NOT NULL,
    account_id     BIGINT,
    transfer_id    BIGINT,
    expected_cents BIGINT,
    actual_cents   BIGINT,
    detail         TEXT        NOT NULL,

    CONSTRAINT reconciliation_mismatches_kind_valid
        CHECK (kind IN ('ACCOUNT_BALANCE', 'TRANSFER_UNBALANCED', 'LEDGER_NOT_ZERO'))
);

CREATE INDEX reconciliation_mismatches_run_id_idx ON reconciliation_mismatches (run_id);

-- Findings are evidence: never edited after the fact.
CREATE TRIGGER reconciliation_runs_append_only
    BEFORE UPDATE OR DELETE ON reconciliation_runs
    FOR EACH ROW EXECUTE FUNCTION reject_modification();

CREATE TRIGGER reconciliation_mismatches_append_only
    BEFORE UPDATE OR DELETE ON reconciliation_mismatches
    FOR EACH ROW EXECUTE FUNCTION reject_modification();
