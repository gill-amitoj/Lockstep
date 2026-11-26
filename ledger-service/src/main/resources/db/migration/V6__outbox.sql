-- V6: transactional outbox.
--
-- The problem: after a transfer commits we must tell Kafka. If we wrote to the
-- database and then called Kafka directly, a crash (or a Kafka outage) between
-- the two would leave a transfer that no other service ever hears about. If we
-- called Kafka first, a rollback would announce a transfer that never happened.
-- There is no transaction that spans PostgreSQL and Kafka.
--
-- The fix: write the event to this table IN THE SAME TRANSACTION as the
-- transfer. Either both exist or neither does. A separate relay then reads
-- unpublished rows, sends them to Kafka, and marks them published. If anything
-- crashes, the row is still here and is sent on the next attempt. The result is
-- "at-least-once" delivery: an event may be sent twice (crash after sending but
-- before marking), never zero times, so consumers deduplicate on event_id.

CREATE TABLE outbox_events (
    event_id       UUID         PRIMARY KEY,   -- consumers dedupe on this
    aggregate_type VARCHAR(50)  NOT NULL,      -- e.g. 'transfer'
    aggregate_id   VARCHAR(100) NOT NULL,      -- e.g. the transfer id
    event_type     VARCHAR(100) NOT NULL,      -- e.g. 'TransferCompleted'
    topic          VARCHAR(200) NOT NULL,
    message_key    VARCHAR(200) NOT NULL,      -- Kafka key: decides the partition, so per-key ordering
    payload        TEXT         NOT NULL,      -- the JSON sent as the Kafka message value
    correlation_id VARCHAR(64),                -- forwarded as a Kafka header
    created_at     TIMESTAMPTZ  NOT NULL,
    published_at   TIMESTAMPTZ                 -- NULL until the relay has sent it
);

-- The relay's query is "unpublished rows, oldest first". A partial index holds
-- only the unpublished rows, so it stays tiny however large the table grows.
CREATE INDEX outbox_events_unpublished_idx ON outbox_events (created_at) WHERE published_at IS NULL;
