-- outbox-publisher schema for PostgreSQL >= 14.
--
-- This file is shipped as a classpath resource by `outbox-schema`. It is NEVER
-- applied automatically by the library — copy it into your migration tool of
-- choice (Flyway, Liquibase, hand-rolled SQL) and run it once per environment.
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
    occurred_at     TIMESTAMP    NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP,
    published_at    TIMESTAMP,
    last_error      TEXT,
    schema_version  SMALLINT     NOT NULL DEFAULT 1
);

-- Partial index backing the relay polling query:
--   SELECT ...
--     FROM outbox
--    WHERE status = 'PENDING'
--      AND (next_attempt_at IS NULL OR next_attempt_at <= now())
--    ORDER BY occurred_at
--    LIMIT :batch
--    FOR UPDATE SKIP LOCKED;
--
-- Key order is (next_attempt_at, occurred_at) so the planner can cheaply skip
-- backed-off rows AND serve the ORDER BY without an extra sort step. `status`
-- is deliberately omitted from the key — it is already pinned by the partial
-- WHERE clause.
CREATE INDEX idx_outbox_pending
    ON outbox (next_attempt_at, occurred_at)
    WHERE status = 'PENDING';

-- Partial index backing the retention purge:
--   DELETE FROM outbox
--    WHERE status = 'SENT'
--      AND published_at < :cutoff;
--
-- Tiny — only indexes rows that have been delivered. Speeds up the cleanup
-- job without inflating the writer hot path.
CREATE INDEX idx_outbox_sent
    ON outbox (published_at)
    WHERE status = 'SENT';
