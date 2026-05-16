-- Mirrors outbox-schema's outbox-publisher.sql plus the example-specific shipments table.
CREATE TABLE outbox (
    id              UUID         PRIMARY KEY,
    aggregate_type  VARCHAR(128) NOT NULL,
    aggregate_id    VARCHAR(128) NOT NULL,
    event_type      VARCHAR(128) NOT NULL,
    payload         BYTEA        NOT NULL,
    content_type    VARCHAR(64)  NOT NULL,
    headers         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    destination     VARCHAR(128),
    occurred_at     TIMESTAMPTZ  NOT NULL,
    schema_version  SMALLINT     NOT NULL DEFAULT 1
);

CREATE TABLE shipments (
    id        UUID         PRIMARY KEY,
    order_id  VARCHAR(128) NOT NULL
);
