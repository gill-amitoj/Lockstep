-- V4: append-only audit log: who did what, and when.
--
-- Rows are written in the SAME transaction as the action they describe, so an
-- action and its audit record commit together or not at all.

CREATE TABLE audit_log (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at    TIMESTAMPTZ  NOT NULL,
    actor          VARCHAR(50)  NOT NULL,  -- username, or '#scheduler' / '#bootstrap' for the app itself
    action         VARCHAR(50)  NOT NULL,  -- e.g. TRANSFER_CREATED, LOGIN_FAILED
    resource_type  VARCHAR(50),
    resource_id    VARCHAR(100),
    details        TEXT,                   -- small JSON object with action-specific facts
    correlation_id VARCHAR(64)             -- ties the record to the request's log lines
);

CREATE INDEX audit_log_occurred_at_idx ON audit_log (occurred_at);
CREATE INDEX audit_log_actor_idx ON audit_log (actor);

-- Enforced by the database, not just by Java having no update method.
-- reject_modification() was created in V1.
CREATE TRIGGER audit_log_append_only
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION reject_modification();

-- Row triggers do not fire for TRUNCATE (it removes all rows without visiting
-- them), so TRUNCATE needs its own statement-level trigger.
CREATE TRIGGER audit_log_no_truncate
    BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();

-- Close the same TRUNCATE gap on the ledger tables from V1.
CREATE TRIGGER entries_no_truncate
    BEFORE TRUNCATE ON entries
    FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();

CREATE TRIGGER transfers_no_truncate
    BEFORE TRUNCATE ON transfers
    FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();
