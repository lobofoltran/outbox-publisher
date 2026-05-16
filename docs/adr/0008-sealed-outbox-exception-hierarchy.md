# ADR-0008: Sealed `OutboxException` hierarchy

- Status: proposed
- Date: 2026-05-16
- Deciders: @lobofoltran
- Tags: outbox-core, error-handling, api

## Context and problem statement

`outbox-core` originally exposed a single `OutboxException` and `JdbcOutbox`
wrapped every `SQLException` in it. That tells the caller "the outbox write
failed" and nothing else. Three concrete failure modes need different responses
from the application:

- a transient deadlock or connection blip → retry the business transaction;
- a duplicate primary key → treat the publish as idempotent success and skip;
- malformed data, or a missing table at startup → fail fast, do not retry.

Because all three arrive as the same exception type, callers either ignore the
distinction (and lose at-least-once guarantees) or parse SQLState codes from
the wrapped cause (and couple themselves to the dialect). Both are wrong.

## Decision drivers

- The library must let the caller decide what to do without inspecting JDBC
  internals.
- The catch-all `OutboxException` in existing application code must keep
  working. We cannot break source compatibility on a 0.x line.
- The set of failure categories is closed. Dialects pick one of N; they do not
  invent new ones. A sealed hierarchy expresses exactly that.
- Java 25 is the project minimum, so `sealed` and exhaustive `switch` over
  exception subtypes are available and idiomatic.

## Considered options

- **Option A — Sealed hierarchy with four leaf subtypes.** Add
  `OutboxTransientException`, `OutboxIntegrityException`, `OutboxDataException`,
  `OutboxConfigurationException`. The base stays catchable; new code can
  pattern-match.
- **Option B — Error-code field on `OutboxException`.** Keep one type, add an
  `OutboxErrorCode` enum field. Simpler, but `instanceof`/`catch` no longer
  classifies failures, and exhaustiveness is lost.
- **Option C — Do nothing, document SQLState parsing.** Pushes the dialect
  knowledge into every consumer. Couples the public API to JDBC.

## Decision outcome

Chosen option: "Option A — Sealed hierarchy with four leaf subtypes", because
it gives callers typed branches and exhaustive `switch`, keeps the existing
`catch (OutboxException e)` working, and confines dialect knowledge to
implementations like `outbox-jdbc`.

The four leaf subtypes and the action they prescribe are:

| Subtype | Meaning | Caller action |
| --- | --- | --- |
| `OutboxTransientException` | Connectivity loss, deadlock, serialization failure, lock timeout. The DB itself classifies the error as retryable. | Retry the surrounding transaction with the application's normal back-off. |
| `OutboxIntegrityException` | A row with the same primary key already exists in `outbox`. | Treat the publish as idempotent success and skip. Do not retry. |
| `OutboxDataException` | Payload or column data is invalid: out-of-range value, length overflow, malformed JSON header. | Abort. The same payload will fail the same way. |
| `OutboxConfigurationException` | The `outbox` table is missing, columns do not match, or the `DataSource` cannot be opened. | Fail fast — ideally at startup. Do not retry; an operator must intervene. |

`OutboxException` becomes `sealed` with exactly those four `permits`. It
remains instantiable for source compatibility — callers that constructed it
directly in tests or compatibility shims keep working — but production code in
`outbox-jdbc` must throw a leaf subtype.

### Dialect classification (forward reference)

The actual translation from `SQLException` → leaf subtype is not part of this
ADR. It will land in `outbox-jdbc` together with the introduction of the
`PostgresDialect` (see roadmap item P0-5). The classification rules below are
the contract that ADR will be expected to honour:

| SQLState class / code | Leaf subtype |
| --- | --- |
| `08*` connection exception | `OutboxTransientException` |
| `40*` transaction rollback / serialization failure / deadlock | `OutboxTransientException` |
| `23*` integrity constraint violation (e.g. `23505` unique violation) | `OutboxIntegrityException` |
| `22*` data exception (truncation, range, invalid datetime, …) | `OutboxDataException` |
| `21*` cardinality violation | `OutboxDataException` |
| `42*` syntax / access rule (e.g. `42P01` undefined table) | `OutboxConfigurationException` |
| `3D*` invalid catalog name | `OutboxConfigurationException` |
| anything else, or no SQLState | `OutboxException` (base) |

The base class remains the safe fallback for SQLStates the dialect does not
recognise; this prevents the dialect from making a wrong category claim on
unfamiliar errors.

### Positive consequences

- Callers can write `catch (OutboxIntegrityException e) { /* skip */ }` without
  inspecting the cause.
- `switch (ex) { case OutboxTransientException t -> …; case … }` is exhaustive
  by construction.
- Adding a new failure category later requires updating `permits`, which is a
  deliberate, reviewed change.
- Consumers that had `catch (OutboxException e)` keep compiling and keep
  catching every variant.

### Negative consequences

- The taxonomy is closed: a new category requires a follow-up release of
  `outbox-core` plus all dialects.
- Misclassification by a dialect is harder to detect than a single generic
  exception, so the dialect's translation logic must be exercised by
  integration tests against the real database.

## Pros and cons of the options

### Option A — Sealed hierarchy

- Good, because callers can branch on type and the compiler enforces
  exhaustiveness.
- Good, because it keeps backward source compatibility for existing
  `catch (OutboxException e)` code.
- Bad, because the taxonomy is closed: extending it later is a breaking-ish
  change for code that relied on exhaustiveness.

### Option B — Error-code field

- Good, because adding categories is non-breaking.
- Bad, because callers cannot use `catch` to classify; everything funnels into
  one block plus an `if` ladder.
- Bad, because the compiler cannot enforce exhaustiveness over an enum without
  a `switch` on every call site.

### Option C — Document SQLState parsing

- Good, because zero code changes.
- Bad, because every consumer must depend on JDBC and learn the dialect's
  SQLStates.
- Bad, because it makes Postgres-specific knowledge part of the public API of
  every consumer.

## Links

- Roadmap item P0-3 — sealed exception hierarchy
- Forward reference: roadmap item P0-5 — `PostgresDialect` translates
  `SQLException` to one of the leaf subtypes
