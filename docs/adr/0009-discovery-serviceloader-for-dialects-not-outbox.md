# ADR-0009: ServiceLoader is for dialect providers, not for `Outbox`

- Status: accepted
- Date: 2026-05-16
- Deciders: @lobofoltran
- Tags: discovery, jpms, serviceloader, api-surface

## Context and problem statement

Earlier docs (ADR-0001 §D5, the README architecture diagram, and the
`Outbox` Javadoc) claimed that the `outbox-jdbc` module exposes `JdbcOutbox`
to applications via `java.util.ServiceLoader` (`provides Outbox with
JdbcOutbox`), and that consumers can therefore obtain the publisher with
`ServiceLoader.load(Outbox.class).findFirst()`.

That claim is wrong as a description of the v1 design and undesirable as
a future direction:

1. The actual `outbox-jdbc/module-info.java` does not declare `provides
   Outbox with JdbcOutbox`. `JdbcOutbox` requires a `connectionSupplier`
   passed to its builder; it cannot be instantiated by the zero-arg
   constructor that `ServiceLoader` requires. There is no sensible default
   for "the connection that participates in the caller's transaction" —
   that decision belongs to the application (Spring's
   `DataSourceUtils.getConnection`, a manual `ThreadLocal`, etc.).
2. `ServiceLoader.load(Outbox.class).findFirst()` is exactly the kind of
   global-singleton, ambient-state lookup the rest of the library
   actively avoids. It hides the wiring decision and breaks the rule that
   `outbox.publish(event)` participates in *the caller's* current
   transaction — which requires the caller to choose how the connection
   is obtained.
3. `ServiceLoader` does have a legitimate role inside `outbox-jdbc`: a
   future `OutboxDialectProvider` SPI for plugging in non-PostgreSQL
   dialects without a hard compile-time dependency. That mechanism is
   purely internal to `outbox-jdbc` and is not part of the `outbox-core`
   contract.

This ADR pins the distinction so the inconsistency does not creep back.

## Decision drivers

- The `outbox-core` API surface must stay minimal and explicit.
- `Outbox` instantiation must be a deliberate wiring decision in the
  application (Spring autoconfig, or a hand-rolled
  `JdbcOutbox.builder()...build()`), not a hidden classpath lookup.
- We still want pluggable database dialects in the future without
  forcing every consumer to take a hard dependency on every dialect's
  driver.

## Considered options

- Option A — `ServiceLoader` discovery for `Outbox` itself, exposed as
  a public contract.
- Option B — No `ServiceLoader` for `Outbox`. Spring autoconfig in
  `outbox-spring` and `JdbcOutbox.builder()` in plain-JDBC apps are the
  only two supported wiring paths. `ServiceLoader` is reserved for an
  internal `OutboxDialectProvider` SPI inside `outbox-jdbc`.
- Option C — A factory class in `outbox-core` (e.g.
  `OutboxFactory.create(...)`) that hides the implementation choice.

## Decision outcome

Chosen option: **Option B**, because it keeps the wiring decision
explicit at the application boundary while still leaving room for
dialect-level pluggability inside `outbox-jdbc`.

### Positive consequences

- The `outbox-core` module exports nothing more than the `Outbox`
  interface, the `OutboxEvent` record, and `OutboxException`. No
  `uses` directive is required, and no consumer can obtain an
  `Outbox` by accident.
- The two supported wiring paths are easy to document and easy to
  audit: "Spring? Use `outbox-spring`. Not Spring? Call
  `JdbcOutbox.builder()`."
- `outbox-jdbc` remains free to introduce an `OutboxDialectProvider`
  SPI in a later version without touching the `outbox-core` contract.

### Negative consequences

- Documentation that previously advertised
  `ServiceLoader.load(Outbox.class)` must be corrected (this ADR, plus
  the changes to `Outbox.java` Javadoc, `README.md`, and `AGENTS.md`
  that ship with it).
- ADR-0001 §D5's mention of "ServiceLoader (`provides Outbox with
  JdbcOutbox`)" is **superseded** by this ADR. ADR-0001 itself is
  preserved as the historical record; readers should follow the
  Status link.

## Pros and cons of the options

### Option A — public `ServiceLoader` for `Outbox`

- Good, because it lets consumers obtain `Outbox` without naming
  `JdbcOutbox`.
- Bad, because `JdbcOutbox` cannot be instantiated by a zero-arg
  constructor — it needs a `connectionSupplier`. A `ServiceLoader`
  hit would have to be followed by a mutator call, which defeats the
  point.
- Bad, because it normalizes hidden global singletons, conflicting
  with the "publish in the caller's transaction" model.

### Option B — `ServiceLoader` only for internal dialect providers (chosen)

- Good, because the `Outbox` wiring path stays explicit.
- Good, because future dialect pluggability is preserved without
  bleeding into `outbox-core`.
- Bad, because it requires a documentation cleanup pass to remove the
  outdated `ServiceLoader` claims.

### Option C — `OutboxFactory.create(...)` in `outbox-core`

- Good, because it gives consumers a single entry point.
- Bad, because `outbox-core` would then need to know about
  `outbox-jdbc` (or use reflection / `ServiceLoader` internally,
  re-introducing Option A's problems).
- Bad, because the factory would either need to take the same
  `connectionSupplier` arg as `JdbcOutbox.builder()` (in which case
  it's a wrapper with no value) or hide it (in which case the
  transactional contract is no longer honest).

## Links

- Supersedes the ServiceLoader paragraph in
  [ADR-0001 §D5](0001-foundations.md) (`provides Outbox with
  JdbcOutbox`). The rest of ADR-0001 stands.
- Related to the `outbox-spring` autoconfiguration (F6) and the
  `JdbcOutbox` builder (F5).
