# ADR-0011: Idempotent publish on duplicate `id`

- Status: accepted
- Date: 2026-05-16
- Deciders: @lobofoltran
- Tags: outbox-jdbc, idempotency, postgres

## Context and problem statement

`OutboxEvent` carries an optional `id` (UUIDv7 by default, or caller-supplied
when the application owns the event identity). When the caller provides an
`id`, replaying the same event — for example after a transient failure that
forced the surrounding transaction to retry — must produce one outbox row,
not two.

Until now, `JdbcOutbox` issued a plain `INSERT`. A duplicate `id` raised a
`SQLException` with SQLState `23505`, which the publisher translated to an
`OutboxIntegrityException`. Callers had to wrap every publish in a try/catch
just to obtain idempotency, and the catch site became a thin layer that
classified an expected outcome as an error.

This is backwards: the outbox table's job is to record events exactly once
per `id`. Duplicate-id should be a no-op, not a failure the application has
to absorb.

## Decision drivers

- The library should make at-least-once retry safe by default. Exactly-once
  semantics on the publisher boundary (one row per `id`) is the smallest
  primitive that achieves that.
- The boundary is the database table, not application code. PostgreSQL has a
  native idempotent insert (`ON CONFLICT (id) DO NOTHING`); using it costs
  nothing and removes the failure mode entirely.
- `OutboxIntegrityException` should remain reserved for genuine integrity
  problems — schema-level constraint violations the operator must investigate.
  Duplicate-id-on-retry is not that.

## Considered options

- Option A — `INSERT ... ON CONFLICT (id) DO NOTHING`. Idempotency moves
  into the dialect's SQL; `JdbcOutbox` no longer surfaces a duplicate-id
  failure to callers.
- Option B — Catch SQLState `23505` in `JdbcOutbox` and silently swallow it.
  Functionally equivalent at the row level, but couples the publisher to
  the dialect's classification and burns one round trip on every duplicate.
- Option C — Document idempotency as a caller responsibility; keep raising
  `OutboxIntegrityException` and let applications decide.

## Decision outcome

Chosen option: **Option A**, because it pushes the idempotency primitive
into the dialect SQL where the database can satisfy it cheaply, and it keeps
`OutboxIntegrityException` reserved for genuine integrity problems.

The PostgreSQL dialect's INSERT is now:

```sql
INSERT INTO outbox (...) VALUES (...) ON CONFLICT (id) DO NOTHING
```

`JdbcOutbox` never sees SQLState `23505` from a duplicate `id`. Other
`23*` violations (foreign key, check constraint) still translate to
`OutboxIntegrityException` per ADR-0008 and the dialect classification.

### Capability advertisement

The dialect declares `DialectCapability.UPSERT_ON_CONFLICT`. Tests that need
to assert idempotent behavior may inspect the capability set; the auto-
detection IT asserts the capability indirectly through the observable
"two publishes, one row" outcome.

### Implementation notes for non-PostgreSQL dialects

A future MySQL/Oracle dialect must satisfy the same observable contract:

- MySQL: `INSERT ... ON DUPLICATE KEY UPDATE id = id` (no-op update).
- Oracle: `MERGE INTO ... USING dual ON (...) WHEN NOT MATCHED THEN INSERT ...`.
- SQL Server: `IF NOT EXISTS (...) INSERT ...` inside the same statement,
  or `MERGE`.

Dialects that cannot satisfy idempotency natively must NOT advertise
`UPSERT_ON_CONFLICT`. The library will continue to surface duplicate-id
collisions as `OutboxIntegrityException` for those dialects until they
implement an equivalent.

### Positive consequences

- Retrying a transaction that already wrote an outbox row is now a no-op
  at the database level.
- The application no longer has to catch `OutboxIntegrityException` for
  the common case.
- Performance is identical to the plain `INSERT` in the happy path; the
  conflict branch is one cheap index probe.

### Negative consequences

- Duplicate-id writes are silently absorbed. If the application supplies
  the same `id` for two genuinely different events (a programming bug),
  the second one is lost without warning. We accept this — it is the
  same semantics as a duplicate primary key in any application table,
  and the alternative (raising on conflict) re-introduces the very
  failure mode this ADR removes.

## Pros and cons of the options

### Option A — `ON CONFLICT (id) DO NOTHING`

- Good, because the database makes the operation idempotent.
- Good, because no exception path is involved on the duplicate-id branch.
- Bad, because it requires a dialect that supports a native upsert
  primitive — but every modern relational DB supports something
  equivalent.

### Option B — Catch `23505` in `JdbcOutbox`

- Good, because it works against any dialect.
- Bad, because the publisher now decodes SQLState classes that the
  dialect already classifies — a layering inversion.
- Bad, because every duplicate write incurs the cost of raising and
  catching an exception.

### Option C — Caller responsibility

- Good, because the library stays minimal.
- Bad, because every consumer reinvents the same try/catch.
- Bad, because the library's at-least-once retry guidance becomes
  conditional on the caller doing this correctly.

## Links

- ADR-0008 — sealed `OutboxException` hierarchy
- ADR-0013 — `OutboxDialect` SPI and auto-detection
- ROADMAP item P0-6
