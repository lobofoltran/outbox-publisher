-- outbox-publisher schema for PostgreSQL >= 14.
--
-- This file is shipped as a classpath resource by `outbox-schema`. It is NEVER
-- applied automatically by the library — copy it into your migration tool of
-- choice (Flyway, Liquibase, hand-rolled SQL) and run it once per environment.
--
-- This script defines ONLY the publisher-side surface: the columns the library
-- writes via `outbox.publish(event)`. Relay-specific columns and indexes live
-- in `outbox-relay-extension.sql` and are applied in addition to this file
-- only when adopting the polling relay. CDC adopters (Debezium etc.) apply
-- this file alone.
--
-- See: AGENTS.md > Table contract  and  README.md > The outbox table contract.

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
