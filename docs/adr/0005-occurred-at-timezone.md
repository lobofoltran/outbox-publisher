# ADR-0005: `occurred_at` uses `TIMESTAMPTZ`, not `TIMESTAMP`

- Status: accepted
- Date: 2026-05-16
- Deciders: @lobofoltran
- Tags: schema, postgres, time, correctness

## Context and problem statement

The original `outbox` table declared every wall-clock column as
`TIMESTAMP` (without time zone) — `occurred_at`, `next_attempt_at`,
`published_at`. The library binds `occurred_at` from a Java `Instant` via
`java.sql.Timestamp.from(instant)`, which converts using the JVM default
time zone. The PostgreSQL JDBC driver then writes the resulting
**wall-clock** representation into a `TIMESTAMP` column, dropping the
offset.

The consequence is silent and only surfaces in heterogeneous environments:
two JVMs running with different `TZ` values that write the same `Instant`
persist different rows. Reading those rows back into an `Instant` via
`ResultSet#getTimestamp(...).toInstant()` re-applies the *reader's* default
zone, so a publisher in `America/Sao_Paulo` and a relay in `UTC` will not
agree on when an event occurred. This is a latent production bug.

## Decision drivers

- Two different JVM time zones must produce the same row for the same `Instant`.
- The library exposes `Instant` everywhere on its Java API (`OutboxEvent#occurredAt`); the storage type should match the API contract.
- Postgres `TIMESTAMPTZ` does the right thing under the hood: it stores UTC and round-trips an `Instant` faithfully when bound through `setObject(..., Instant, Types.TIMESTAMP_WITH_TIMEZONE)`.
- The library is pre-1.0; the breaking schema change is free now and impossible later without a migration window.

## Considered options

- **Option A — Keep `TIMESTAMP`, document "set TZ=UTC on every JVM".** Push the correctness burden onto operators.
- **Option B — Switch to `TIMESTAMPTZ`** and make the binding refactor a follow-up so the schema change can land independently.
- **Option C — Switch to `BIGINT` storing epoch milliseconds.** Side-step JDBC types entirely.

## Decision outcome

Chosen option: **Option B**, because it is the only one that fixes the bug without depending on operator discipline and without inventing a new wire type.

This ADR locks the storage decision. The corresponding driver-side binding refactor — replacing every `Timestamp.from(Instant)` with `setObject(i, instant, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)` via the dialect SPI — is intentionally **not** in scope here. The schema change is safe in isolation: PostgreSQL accepts `Timestamp.from(Instant)` writes into a `TIMESTAMPTZ` column today, with the same JVM-zone-dependent bug the new schema is meant to eliminate. That bug is fixed by the binding refactor (Roadmap P0-1 / Agent 5), guarded by a cross-TZ integration test.

### Positive consequences

- The on-disk representation is unambiguous: UTC-anchored, with offset preserved across writes.
- Once the binding refactor lands, two JVMs in different zones writing the same `Instant` produce the same row. A test runs the writer with `TZ=America/Sao_Paulo` and reads it back with `TZ=UTC`, asserting `Instant` equality.
- The relay extension's `next_attempt_at` and `published_at` columns are also `TIMESTAMPTZ`, so the same property holds for the polling relay.

### Negative consequences

- Pre-existing deployments — there are none yet, but this is the rule going forward — would need a `ALTER TABLE ... TYPE TIMESTAMPTZ USING (occurred_at AT TIME ZONE 'UTC')` migration. Not free, but a one-time cost.
- Until the binding refactor merges, the library still binds `Timestamp.from(Instant)` into a `TIMESTAMPTZ` column. Postgres accepts this, but the cross-TZ correctness property does not yet hold; this is documented and tracked in Roadmap P0-1.

## Pros and cons of the options

### Option A — Keep `TIMESTAMP`, document `TZ=UTC` everywhere

- Bad, because correctness depends on every operator setting a JVM-level environment variable.
- Bad, because it loses the offset, so any deployment that does not enforce UTC silently corrupts timestamps.
- Good, because it requires no schema or driver work today.

### Option B — `TIMESTAMPTZ` with a follow-up binding refactor

- Good, because the storage matches the `Instant` semantics the library already exposes.
- Good, because Postgres handles offset round-tripping natively.
- Bad, because it is a breaking schema change for any pre-1.0 adopter.

### Option C — Store epoch millis as `BIGINT`

- Good, because it is unambiguous.
- Bad, because it gives up Postgres date/time functions used by the relay's polling query (`next_attempt_at <= now()`) and by ad-hoc operations.
- Bad, because it forces every consumer (CDC sinks, dashboards, debug queries) to translate longs back to timestamps in their own layer.

## Links

- Roadmap P0-1
- Related to ADR-0007 (schema split) — both ship in the same commit because the relay extension introduces additional `TIMESTAMPTZ` columns.
