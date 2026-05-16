# ADR-0004: Metrics surface and cardinality policy

- Status: accepted
- Date: 2026-05-16
- Deciders: @lobofoltran
- Tags: observability, metrics, cardinality

## Context and problem statement

`outbox-micrometer` adds Micrometer instrumentation to every `publish` call. Two design questions need an explicit, durable answer before the module ships:

1. **Which meters do we emit?** Naming and structure are part of the public observability contract and changing them later breaks dashboards and alerts.
2. **Which dimensions (tags) attach to those meters?** Every tag multiplies metric cardinality on the backend. A bad choice here can crash a Prometheus instance long after the offending event flows through.

Both decisions are easier to lock now than to rewrite later.

## Decision drivers

- **Bounded cardinality.** Tags must come from a small, controlled set known at design time. We will not tag anything that the application can grow without bound.
- **Operational usefulness.** The default tags must support the questions an operator actually asks ("how is publish latency split by event type?", "are failures spiking on a specific aggregate?").
- **No leaks of business identity.** `aggregate_id` and tenant scopes never enter a metric label.
- **Zero new dependencies in the core path.** `outbox-core` and `outbox-jdbc` stay Micrometer-free; only `outbox-micrometer` depends on `io.micrometer:micrometer-core`.

## Considered options

### Meter set

- **M1 — One `Timer` + one `DistributionSummary` for payload size** (chosen). Tagged with a fixed list. Stable, minimal, covers latency and bytes.
- M2 — One `Counter` per success/failure result.
- M3 — Custom Micrometer `Observation` API.

### Tag set

- **T1 — `aggregate_type`, `event_type`, `result`** on the timer (chosen).
- T2 — Same plus `destination`.
- T3 — Same plus `aggregate_id`.

## Decision outcome

**M1 + T1.** The decorator emits exactly two meters and tags them with three dimensions on the timer / two on the summary. No other tags are added by the library; consumers may add their own via `MeterFilter` on their `MeterRegistry`.

### Meters

| Meter                  | Type                                    | Tags                                              | Purpose                                  |
|------------------------|-----------------------------------------|---------------------------------------------------|------------------------------------------|
| `outbox.publish`       | `Timer`                                 | `aggregate_type`, `event_type`, `result`          | Latency of `publish(...)`                |
| `outbox.publish.bytes` | `DistributionSummary` (base unit `bytes`) | `aggregate_type`, `event_type`                  | Size distribution of the event payload   |

`result` is `success` or `failure`.

### Excluded tags

The following tags are **deliberately not** emitted:

- **`aggregate_id`.** Unbounded by construction (`ord-1`, `ord-2`, …) — would blow up cardinality on day one and leaks business identity into the metrics store.
- **`destination`.** Often dynamic and tenant-scoped (`orders.events.tenant-XYZ`) — same cardinality risk. Operators wanting per-destination dashboards can derive them from logs or traces.
- **`status`**, **`attempts`**, **`schema_version`.** These are relay/storage concerns, not publish-time observations.

Consumers needing any of these can introduce them locally via `MeterFilter#commonTags` on their own `MeterRegistry`, accepting the cardinality cost knowingly.

### Why Timer and DistributionSummary

- `Timer` aggregates count, sum and (optionally) percentiles in one meter. Cheaper than a separate `Counter` + `Timer`, and the `result` tag splits successes from failures without extra meters.
- `DistributionSummary` for payload size is independent from latency: payloads are highly skewed (some events carry kilobytes, others bytes) and benefit from histogram support that callers can enable per-registry.

### Why a decorator and not a `BeanPostProcessor` inside `outbox-jdbc`

The decorator pattern keeps `outbox-jdbc` Micrometer-free (ADR-0001 D7 keeps each module to its own concern). `outbox-spring`'s autoconfig registers a `BeanPostProcessor` that wraps any `Outbox` bean — including user-provided ones — when both Micrometer and `outbox-micrometer` are present.

### Opt-out

`io.github.lobofoltran.outbox.metrics.enabled=false` disables the wrapping. The autoconfig also backs off automatically when `MeterRegistry` is absent from the context, when `outbox-micrometer` is missing from the classpath, or when the bean to wrap is already a `MeteredOutbox` (no double-wrapping).

### Positive consequences

- Dashboards built against `outbox.publish` / `outbox.publish.bytes` survive every future change to the storage schema or the relay protocol — they only depend on the `Outbox.publish` API surface.
- Cardinality is provably bounded by the cross-product of `aggregate_type × event_type × result`. In a typical service this stays in the dozens to low hundreds.

### Negative consequences

- Operators who want per-tenant or per-destination splits must do that work themselves via `MeterFilter`.
- Adding a fourth tag in the future is a breaking change for backends that key by tag set (Prometheus does). Such a change requires a new ADR and a documented migration.

## Pros and cons of the rejected options

### M2 — separate success/failure counters

- Bad: doubles the meter surface for what a single `result` tag captures.
- Bad: latency and counts diverge across two meters; aggregation queries get harder.

### M3 — `Observation` API

- Good: would also produce traces for free when an `ObservationRegistry` is configured.
- Bad: increases dependency weight on plain Micrometer users; `Observation` is best-of-breed but not yet the de-facto convention. We can revisit once it becomes universally adopted.

### T2 — include `destination`

- Bad: tenant-scoped destinations (`orders.tenant-XYZ`) routinely break Prometheus instances.
- Good: per-destination dashboards. Consumers can add this themselves with their own risk tolerance.

### T3 — include `aggregate_id`

- Bad: unbounded cardinality by definition. No mitigation makes this safe in a metrics store.

## Compatibility

- Adding a new meter or a new value for `result` is a non-breaking change.
- Removing a tag, renaming a meter, or changing tag semantics is a **breaking** change requiring a new ADR.

## Links

- Operationalized in **F6.5** (this PR): `outbox-micrometer` ships `MeteredOutbox`; `outbox-spring`'s `OutboxAutoConfiguration` adds a nested `MetricsConfiguration` that wraps `Outbox` beans via a `BeanPostProcessor`.
- Refines ADR-0001 D5 (JPMS strict; `outbox-micrometer` is JPMS-modular) and D7 (logging through SLF4J; metrics here are the only optional cross-cutting concern in the library).
