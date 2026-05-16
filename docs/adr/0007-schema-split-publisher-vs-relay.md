# ADR-0007: Split the outbox schema by responsibility (publisher vs. relay)

- Status: accepted
- Date: 2026-05-16
- Deciders: @lobofoltran
- Tags: schema, postgres, contract, packaging

## Context and problem statement

`outbox-schema` originally shipped a single SQL file
(`sql/postgres/outbox.sql`) that mixed two distinct concerns into one
table definition:

- **Publisher columns** that the library writes via
  `outbox.publish(event)`: `id, aggregate_type, aggregate_id, event_type,
  payload, content_type, headers, destination, occurred_at,
  schema_version`.
- **Relay-lifecycle columns** consumed only by a polling relay:
  `status, attempts, next_attempt_at, published_at, last_error`, plus the
  partial indexes `idx_outbox_pending` and `idx_outbox_sent`.

The library does not write the relay-side columns — they exist solely to
serve `outbox-relay`'s `SELECT ... FOR UPDATE SKIP LOCKED` polling query
and its retention-purge `DELETE`. Yet every adopter paid the cost:

- **CDC adopters (Debezium etc.)** never need a `status` lifecycle. The
  CDC pipeline reads the WAL on insert; the row is meant to be
  *delete-after-insert*. A `status` column with a `DEFAULT 'PENDING'` and
  the partial index that goes with it are pure overhead — extra storage,
  extra writer-side index maintenance on every `INSERT`.
- **Polling adopters who want to add a column or partial index later**
  had no incremental story; the schema was monolithic.
- **Anyone reading the schema** had to reverse-engineer which columns the
  library actually writes vs. which exist for the relay.

The principle stated in the architecture target is that the **table** is
the integration boundary, and the publisher should not impose relay
semantics on adopters who do not run the relay.

## Decision drivers

- Adopters who do not run a polling relay should not pay for relay-only columns or indexes.
- The library should be honest about which columns it writes — that is the publisher contract.
- A polling relay can be added to a CDC-shaped deployment later without a destructive migration.
- The split must not introduce new complexity for the common case (polling relay): apply two scripts in order is not meaningfully harder than apply one script.

## Considered options

- **Option A — Keep one monolithic script.** Status quo.
- **Option B — Split into two scripts under `outbox-schema/src/main/resources/sql/postgres/`:** `outbox-publisher.sql` (mandatory) and `outbox-relay-extension.sql` (optional, idempotent).
- **Option C — Move the relay extension into the `outbox-relay` repository.** Strict separation by ownership.

## Decision outcome

Chosen option: **Option B**, because it expresses the boundary in the schema itself, costs nothing for polling adopters, and keeps the existing architectural invariant "no Java dependency between this repo and `outbox-relay`" (the **table** is the contract, not a Java type).

The relay extension lives in this repository on purpose: relay-side schema is shaped by the library's column choices (`occurred_at` ordering, `id` as primary key) and must move in lockstep with them. Hosting it next to `outbox-publisher.sql` makes that lockstep visible and reviewable.

### Positive consequences

- CDC adopters apply only `outbox-publisher.sql` and pay zero relay overhead.
- Polling adopters apply both files in order. The two-step sequence is documented in the README adoption-mode table.
- Hybrid / unsure adopters apply only the publisher script today; the relay extension is fully idempotent (`ADD COLUMN IF NOT EXISTS`, `CREATE INDEX IF NOT EXISTS`) and can be applied later without data migration.
- The schema versioning column (`schema_version`) lives in the publisher script. The relay extension does not bump it; relay-side versioning is owned by `outbox-relay`'s own roadmap.
- `SchemaResourceTest` asserts the split is real: the publisher script does not contain any relay column or index name, and the relay extension contains all of them.

### Negative consequences

- Two SQL files instead of one — a trivial UX cost, documented in the README and AGENTS.
- The relay extension is an `ALTER TABLE`, not part of a single `CREATE TABLE`. The DDL diff is slightly less readable than the original combined form, but the comments preserve the design rationale.
- Tests that reset the schema between cases must apply both scripts (in order). `AbstractPostgresIT#recreateTable` was adjusted accordingly; it is the single test-side touchpoint.

## Pros and cons of the options

### Option A — Monolithic `outbox.sql`

- Good, because there is only one file to apply.
- Bad, because every adopter pays for relay-only columns and indexes, including those who explicitly chose CDC to avoid the relay.
- Bad, because the schema lies about which columns the publisher writes.

### Option B — Two scripts in `outbox-schema`

- Good, because the publisher contract is precisely the columns the library writes.
- Good, because CDC adopters opt out of the relay extension by simply not applying it.
- Good, because the relay extension is idempotent: a deployment can switch modes without rebuilding the table.
- Bad, because adopters must apply two files when they want the polling relay.

### Option C — Relay extension lives in `outbox-relay`

- Good, because ownership is by repository.
- Bad, because the relay-side schema is constrained by `outbox-publisher`'s column choices; co-locating them is the only honest way to keep them in lockstep.
- Bad, because it forces consumers who only adopt the publisher to nevertheless track a second repository for "the rest of the schema". Boundary muddled, not clarified.

## Links

- Roadmap P0-2
- Related to ADR-0005 — both decisions ship together; the relay extension introduces additional `TIMESTAMPTZ` columns covered there.
