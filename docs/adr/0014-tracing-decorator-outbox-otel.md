# ADR-0014: Tracing decorator with OpenTelemetry semantic conventions

- Status: accepted
- Date: 2026-05-16
- Deciders: @lobofoltran
- Tags: observability, tracing, opentelemetry

## Context and problem statement

`outbox-publisher` already has metrics (ADR-0004). The next observability gap is **tracing**: every call to `outbox.publish` happens inside the caller's business transaction, but downstream relays and CDC pipelines have no way of correlating their work with the originating producer span. Operators end up with broken traces at the database boundary.

Three design questions need an explicit, durable answer before the module ships:

1. **Where does tracing live?** A new module, or instrumentation woven into an existing one?
2. **Which spans and attributes do we emit?** This becomes the public tracing contract — like the meters from ADR-0004, breaking it later breaks dashboards, alert rules and trace queries.
3. **How is OpenTelemetry's API exposed to consumers?** Pin a version (forces upgrade in lockstep with consumers), require provided (consumers bring their own), or hide it behind a port?

## Decision drivers

- **No new mandatory dependency.** `outbox-core` and `outbox-jdbc` stay OTel-free. Consumers without a tracer pay nothing.
- **Bounded cardinality on span names.** Span names are interned by tracing backends much like metric names. The same rules from ADR-0004 (no `aggregate_id`, no `destination` in low-cardinality positions) apply here.
- **Standard wire format.** Use `messaging.*` semantic conventions so existing tooling (Tempo, Jaeger, Honeycomb, vendor APMs) renders the spans correctly without per-tool customization.
- **Decorator parity.** The shape of `outbox-otel` should mirror `outbox-micrometer` (constructor decorator + Spring `BeanPostProcessor`) so adopters learn one pattern.

## Considered options

### Module placement

- **D1 — New `outbox-otel` module containing `TracedOutbox` decorator** (chosen). Mirrors `outbox-micrometer`.
- D2 — Add tracing inside `outbox-jdbc`. Rejected: violates ADR-0001 D7 (each module to its own concern) and forces every JDBC user to drag OpenTelemetry classes onto their classpath.
- D3 — Hide tracing behind a custom port in `outbox-core`. Rejected: the OTel `Tracer` API *is* the port; reinventing it adds friction for users who already configured their own `OpenTelemetry` bean.

### Span set and attributes

- **S1 — One producer span per `publish`, one per `publishAll`** (chosen). Single-event spans carry the message id; batch spans carry `messaging.batch.message_count`. Both use span kind `PRODUCER`.
- S2 — One root span plus one child per event in `publishAll`. Rejected: explodes span volume on heavy batches and provides no operational signal beyond `messaging.batch.message_count`.

### OTel dependency scope

- **P1 — `<scope>provided</scope>`** (chosen). Consumers bring their own OpenTelemetry version (typically aligned with their agent). The module compiles against a known-good API and is binary-compatible with newer 1.x versions.
- P2 — `<scope>compile</scope>` with a pinned version. Rejected: forces the version of OpenTelemetry on every consumer; collisions with the OTel Java agent are common.
- P3 — Soft dependency loaded by reflection. Rejected: useless complication once the module is opt-in already.

## Decision outcome

**D1 + S1 + P1.** The library ships an optional `outbox-otel` module that contains a single decorator, `TracedOutbox`, and registers a Spring `BeanPostProcessor` (in `outbox-spring`) under the same wiring style as `MeteredOutbox`.

### Spans

| Span name | Span kind | Triggered by | Attributes |
| --- | --- | --- | --- |
| `outbox publish` | `PRODUCER` | `publish(OutboxEvent)` | `messaging.system=outbox`, `messaging.operation=publish`, `messaging.message.id`, `messaging.destination.name` (when non-null), `outbox.aggregate_type`, `outbox.event_type` |
| `outbox publish_batch` | `PRODUCER` | `publishAll(Iterable<OutboxEvent>)` | `messaging.system=outbox`, `messaging.operation=publish_batch`, `messaging.batch.message_count` |

`messaging.*` keys follow the OpenTelemetry semantic conventions for messaging systems. The two `outbox.*` keys are local extensions used in single-event spans where the conventions do not provide a slot for aggregate type / event type.

### Excluded attributes

The following are **deliberately not** placed on the span name or used as low-cardinality attributes:

- `messaging.destination.name` — recorded as an attribute (not in the span name) so traces are queryable per destination without exploding span-name cardinality on tenant-scoped destinations such as `orders.tenant-XYZ`.
- `aggregate_id` — never recorded. Unbounded by definition; same reasoning as ADR-0004.

### Errors

On exception, the span status is set to `ERROR` and the exception is recorded via `Span.recordException(...)`. The original exception is rethrown unchanged — the decorator never swallows or transforms exceptions.

### Why a decorator and not a `BeanPostProcessor` inside `outbox-jdbc`

Same rationale as ADR-0004: the decorator pattern keeps `outbox-jdbc` OTel-free and lets the cross-cutting wiring live in `outbox-spring` next to the metrics wiring.

### Opt-out

`io.github.lobofoltran.outbox.tracing.enabled=false` disables the wrapping. The autoconfig also backs off automatically when no `OpenTelemetry` bean is present, when `outbox-otel` is missing from the classpath, or when the bean to wrap is already a `TracedOutbox` (no double-wrapping).

### Wrapping scope

The current `BeanPostProcessor` wraps **every** `Outbox` bean in the context, mirroring the metrics processor. Restricting the scope to "only the bean produced by the auto-configuration" is tracked under P2-2 and would be applied to both processors symmetrically, not just tracing.

### Positive consequences

- Trace queries built against `outbox publish` and `outbox publish_batch` survive future changes to the storage schema or relay protocol — they only depend on the `Outbox` API surface.
- Consumers who run the OpenTelemetry Java agent get the spans automatically without any code change. Manual SDK setups work just as well via the `OpenTelemetry` bean.
- `outbox-jdbc` and `outbox-core` keep zero OpenTelemetry weight.

### Negative consequences

- Adopters with two different OpenTelemetry versions (e.g. the agent's bundled API and a manually pinned API) may run into classloader collisions. This is intrinsic to OTel's deployment model; documenting `<scope>provided</scope>` is the only reasonable mitigation.
- Renaming a span or removing an attribute is a **breaking** change requiring a new ADR.

## Pros and cons of the rejected options

### D2 — tracing in `outbox-jdbc`

- Bad: forces OTel on every JDBC user.
- Bad: couples the SQL layer to a tracing API it does not need.

### D3 — custom port in `outbox-core`

- Bad: reinvents the OpenTelemetry `Tracer` interface.
- Bad: users who already wired up OTel have to write an adapter.

### S2 — one child span per event in batches

- Good: per-event timing inside a batch.
- Bad: a 1,000-event batch produces 1,001 spans, dwarfing useful signal in tracing UIs.

### P2 — pinned OTel compile dependency

- Good: predictable transitive resolution.
- Bad: forces consumers onto whichever OTel version this library happens to track, breaking those running the OTel Java agent.

## Compatibility

- Adding a new attribute or a new span kind is a non-breaking change.
- Renaming a span, removing an attribute, or changing attribute semantics is a **breaking** change requiring a new ADR.

## Links

- Refines ADR-0001 D5 (JPMS strict; `outbox-otel` is JPMS-modular like `outbox-micrometer`) and D7 (each module to its own concern).
- Refines ADR-0004: same cardinality discipline applied to span names.
- Operationalized in P2-1: `outbox-otel` ships `TracedOutbox`; `outbox-spring`'s `OutboxAutoConfiguration` adds a nested `TracingConfiguration` that wraps `Outbox` beans via a `BeanPostProcessor`.
