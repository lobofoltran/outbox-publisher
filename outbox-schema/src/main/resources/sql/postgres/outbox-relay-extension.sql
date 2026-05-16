-- outbox-relay-extension schema for PostgreSQL >= 14.
--
-- Optional companion to `outbox-publisher.sql`. Apply this script ONLY when
-- adopting the polling-relay model (see `outbox-relay`). CDC adopters
-- (Debezium etc.) skip this file entirely — they consume the WAL, not the
-- relay lifecycle columns.
--
-- The script is idempotent (`ADD COLUMN IF NOT EXISTS`,
-- `CREATE INDEX IF NOT EXISTS`) so it is safe to apply on top of an existing
-- publisher table without data migration. This is the "I started with CDC and
-- now want to add a polling relay" path documented in README.md.
--
-- See: AGENTS.md > Table contract  and  README.md > The outbox table contract.

ALTER TABLE outbox
    ADD COLUMN IF NOT EXISTS status          VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN IF NOT EXISTS attempts        INT         NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS published_at    TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_error      TEXT;

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
CREATE INDEX IF NOT EXISTS idx_outbox_pending
    ON outbox (next_attempt_at, occurred_at)
    WHERE status = 'PENDING';

-- Partial index backing the retention purge:
--   DELETE FROM outbox
--    WHERE status = 'SENT'
--      AND published_at < :cutoff;
--
-- Tiny — only indexes rows that have been delivered. Speeds up the cleanup
-- job without inflating the writer hot path.
CREATE INDEX IF NOT EXISTS idx_outbox_sent
    ON outbox (published_at)
    WHERE status = 'SENT';
