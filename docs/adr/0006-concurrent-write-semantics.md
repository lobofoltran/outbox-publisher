# ADR-0006: Concurrent write semantics

- Status: accepted
- Date: 2026-05-16
- Deciders: @lobofoltran
- Tags: concurrency, transactions, ordering

## Context and problem statement

`Outbox.publish(event)` is invoked from inside the caller's transaction by
arbitrary application code, including code running concurrently across
multiple JVMs against the same database. We need to be explicit about what
guarantees the library makes (and does not make) regarding concurrency,
ordering, and isolation, so that consumers do not build assumptions that the
library is not in a position to honor.

## Decision drivers

- The library is a thin INSERT into an `outbox` table; it does not own a
  transaction, does not own a connection pool, and does not coordinate across
  JVMs.
- The relay (a separate concern, separate repository) is responsible for the
  *delivery* ordering observed by consumers; the library is responsible for
  the *write* ordering observed in the table.
- Adopters need a clear, short, written contract — not a guess.

## Considered options

- Option A — Document the existing behavior as the contract.
- Option B — Add per-aggregate serialization (e.g. advisory locks keyed on
  `aggregate_id`) inside `publish()` to give cross-transaction ordering
  guarantees.
- Option C — Document only intra-transaction ordering and refuse to discuss
  the multi-JVM case.

## Decision outcome

Chosen option: **Option A**, because the library already behaves correctly
under concurrent load by virtue of running inside the caller's transaction
and letting the database handle MVCC; the contract just has to say so.

The contract is:

1. **Transactional participation.** `publish(event)` issues an INSERT on the
   caller-supplied `Connection` (see ADR-0010). It does not commit, roll
   back, or otherwise mutate transaction state. The INSERT is therefore
   atomic with the caller's business writes.

2. **Multi-JVM safety.** Concurrent writers from multiple JVMs are safe by
   construction. The library performs no in-memory coordination; correctness
   comes from the database's MVCC and the `PRIMARY KEY (id)` constraint. The
   `id` column is the idempotency key (ADR-0011).

3. **Intra-transaction ordering.** Within a single caller transaction,
   multiple `publish()` calls produce rows in the same order as the calls.
   This is guaranteed because we issue one INSERT per call on a single
   connection. `publishAll(...)` (ADR-0012) is equivalent to N sequential
   `publish()` calls and preserves this ordering.

4. **Cross-transaction ordering is not guaranteed.** If transaction T1 calls
   `publish(a)` and transaction T2 (on a different JVM, possibly a different
   schema connection pool) calls `publish(b)`, the library makes no claim
   about the relative order of `a` and `b` in the table. The table is the
   wrong place to express cross-aggregate ordering; the relay sees the
   commit-order projection that the database produces and is the layer that
   chooses how to expose ordering to consumers.

5. **`occurred_at` is informational, not authoritative.** Wall-clock times
   are TIMESTAMPTZ (ADR-0005). They are useful for observability and for the
   relay's `ORDER BY occurred_at` polling clause; they are not a total order.

6. **No advisory locks.** The library does not acquire any database-level
   lock (no `pg_advisory_xact_lock`, no SELECT FOR UPDATE) to enforce
   ordering. Adopters that need stronger guarantees per aggregate must do
   that in their own write path.

### Positive consequences

- Behavior is explicit and unsurprising. The library's job is "write a row";
  it does that and no more.
- Performance is not penalized by coordination the library cannot meaningfully
  enforce.
- The boundary between `outbox-publisher` and the relay stays clean: write
  vs. delivery.

### Negative consequences

- Adopters that want a strict per-aggregate cross-transaction order must
  either (a) serialize their own writes upstream, or (b) rely on a relay
  configuration that re-orders by `(aggregate_id, occurred_at)`. The library
  does not help here.
- Wall-clock skew across JVMs can produce surprising `occurred_at` orderings
  in the table. ADR-0005 mitigates the timezone aspect; clock skew itself is
  out of scope.

## Pros and cons of the options

### Option A — Document existing behavior

- Good, because no code changes; the contract matches reality.
- Good, because the library stays a thin, testable INSERT.
- Bad, because adopters must read this ADR before assuming cross-transaction
  ordering.

### Option B — Serialize per aggregate inside `publish()`

- Good, because some adopters would get cross-transaction ordering for free.
- Bad, because advisory locks held inside the caller's transaction extend
  contention and lock-hold time in ways the library cannot reason about.
- Bad, because the right layer for "deliver in order per aggregate" is the
  relay, not the writer.
- Bad, because it would change the connection-supplier contract (ADR-0010)
  by introducing implicit DB calls beyond a single INSERT.

### Option C — Refuse to discuss multi-JVM

- Good, because shorter document.
- Bad, because adopters will assume something, and it will be wrong.

## Links

- Related: ADR-0010 (Connection-supplier contract).
- Related: ADR-0011 (Idempotent publish on duplicate id).
- Related: ADR-0013 (OutboxDialect SPI and auto-detection) — dialect controls
  exactly which INSERT SQL runs, but does not change these semantics.
- Related: ADR-0005 (`occurred_at` timezone).
