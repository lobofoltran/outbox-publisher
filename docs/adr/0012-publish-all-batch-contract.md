# ADR-0012: `publishAll` batch contract

- Status: accepted
- Date: 2026-05-16
- Deciders: @lobofoltran
- Tags: outbox-core, outbox-jdbc, performance, api-surface

## Context and problem statement

`Outbox` had a single method, `publish(OutboxEvent)`. A handler that emits
multiple events per business transaction (a saga step writing two domain
events, an aggregate that fans out to several topics) called `publish`
repeatedly. Each call paid a round trip to the database and a fresh
`PreparedStatement` allocation.

For large batches, the cumulative cost is non-trivial. More importantly,
the API gave callers no way to express "these N events are one logical
batch" — every call looked atomic and individually retryable, even though
in practice they all live or die with the surrounding transaction.

## Decision drivers

- The publisher should be able to write N events in O(1) round trips when
  the underlying driver supports batched JDBC.
- The API change must be source-compatible. Existing implementations of
  `Outbox` (in tests, in user code) must keep compiling.
- Per-event ordering and per-event metrics must be preserved: a batch of
  three events must record three `outbox.publish.bytes` samples, not one.

## Considered options

- Option A — Add `default void publishAll(Iterable<OutboxEvent> events)`
  on `Outbox` that loops over `publish`. `JdbcOutbox` overrides with a
  true `addBatch` / `executeBatch`. `MeteredOutbox` overrides to record
  batch-level metrics around per-event metrics.
- Option B — Add a separate `BatchOutbox` interface. Callers that need
  batching pick that interface explicitly.
- Option C — Accept a varargs overload `publish(OutboxEvent...)`. Smaller
  surface change, but ambiguous when callers pass an existing collection.

## Decision outcome

Chosen option: **Option A**, because it preserves the single-method spirit
of `Outbox`, costs implementations nothing if they do not need batching,
and lets `JdbcOutbox` and `MeteredOutbox` opt into the optimization without
changing the wiring.

### Contract

| Aspect | Behavior |
| --- | --- |
| Empty `Iterable` | No-op. No round trip. |
| `null` `Iterable` | `NullPointerException`. |
| `null` element | `NullPointerException`. The implementation MAY validate eagerly (before any write) or lazily (per element); `JdbcOutbox` validates eagerly so the failure happens before the database is touched. |
| Atomicity | A `publishAll` call inherits the caller's transaction. Either every event commits with the surrounding business writes, or none does. |
| Ordering | Events are persisted in iteration order. Concurrent `publishAll` calls on the same `JdbcOutbox` may interleave at the database level — order is per-call, not global. |
| Per-event metrics | `outbox.publish.bytes` records once per event, regardless of which entry point was used. |
| Batch metrics | `outbox.publish.batch` (timer, `result` tag only) and `outbox.publish.batch.size` (untagged distribution summary) are emitted per `publishAll` call. The single-event `publish` path does NOT emit these — its latency is captured by `outbox.publish` instead. |
| Idempotency | Each event is idempotent on `id` per ADR-0011. A batch containing the same `id` twice writes the row once. |

### Default implementation

```java
default void publishAll(Iterable<OutboxEvent> events) {
    Objects.requireNonNull(events, "events must not be null");
    for (OutboxEvent event : events) {
        publish(event);
    }
}
```

This default is in `outbox-core`. Source-compatible with every prior
`Outbox` implementation: nothing breaks if the caller upgrades and never
calls `publishAll`.

### `JdbcOutbox` override

`publishAll` materializes the iterable, validates non-null elements,
asserts manual transaction mode, resolves the dialect, and uses
`PreparedStatement.addBatch()` / `executeBatch()` against the dialect's
INSERT SQL. `publish(event)` now delegates to `publishAll(List.of(event))`
to share the code path; a single-event call still does one round trip.

### `MeteredOutbox` override

The decorator wraps the batch in a single timer + a batch-size summary,
then iterates the materialized batch to record per-event payload bytes.
Per-event timer samples are not recorded for `publishAll` — the timing
question for a batch is "how long did the batch take", not "how long
did each row take", and recording N timers for one round trip would
double-count latency on the single underlying operation.

### Positive consequences

- Multi-event handlers shrink from O(N) round trips to O(1).
- The API stays small: one extra method, default-implemented.
- Metrics distinguish single-event vs batched call sites at scrape time.

### Negative consequences

- `MeteredOutbox` introduces two new meter names (`outbox.publish.batch`,
  `outbox.publish.batch.size`). Dashboards that listed every metric by
  hand need an update; dashboards that match `outbox.publish.*` keep
  working.
- The default loop and the JDBC `addBatch` path observe slightly different
  failure modes: the default fails fast on the offending element; the
  batched path may fail at `executeBatch` time after every `addBatch`
  has been issued. Both still abort the surrounding transaction.

## Pros and cons of the options

### Option A — Default `publishAll` + JDBC override

- Good, because it preserves source compatibility.
- Good, because each implementation chooses how to honor the contract.
- Bad, because the default is slower than the JDBC override; users
  writing custom `Outbox` implementations might forget to override it.

### Option B — Separate `BatchOutbox` interface

- Good, because batching is opt-in by type.
- Bad, because every Spring-wired `Outbox` consumer would need to know
  whether batching is available — defeating the single-bean wiring.
- Bad, because two interfaces with overlapping semantics are harder to
  document than one.

### Option C — Varargs overload

- Good, because the surface change is minimal.
- Bad, because passing an existing `List<OutboxEvent>` requires
  `.toArray(new OutboxEvent[0])` — awkward, and at that point the
  varargs offers no advantage over `Iterable`.
- Bad, because varargs precludes lazy iteration.

## Links

- ADR-0004 — metrics and cardinality
- ADR-0011 — idempotent publish on duplicate id
- ROADMAP item P0-7
