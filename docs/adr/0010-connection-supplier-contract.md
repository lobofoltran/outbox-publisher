# ADR-0010: `ConnectionSupplier` contract — manual transactions, no-close

- Status: accepted
- Date: 2026-05-16
- Deciders: @lobofoltran
- Tags: outbox-jdbc, transactions, api-surface

## Context and problem statement

`JdbcOutbox` obtains the JDBC `Connection` it writes to from a caller-supplied
`ConnectionSupplier`. Until now the contract was implicit: callers had to
infer from the Javadoc that the supplier should return the connection bound
to the surrounding transaction and that `JdbcOutbox` would not close it. Two
real misconfigurations followed:

1. Callers wired `() -> dataSource.getConnection()` directly. Outside of
   Spring this works; inside Spring it returns a fresh, untransacted
   connection and silently breaks the atomicity guarantee — the outbox row
   commits even when the business transaction rolls back.
2. Callers ran with `autoCommit=true`. The INSERT commits immediately,
   visible to the relay before the business writes are durable. Same outcome:
   the pattern is defeated and the failure is silent.

Both cases are easy to write, hard to detect from logs, and catastrophic
in production.

## Decision drivers

- Misconfiguration must surface as a loud, typed exception, not silent
  data corruption.
- The contract must accommodate both Spring (`DataSourceUtils`) and plain
  JDBC, without prescribing a framework.
- The library never owns the connection lifecycle: that belongs to the
  surrounding transaction manager or to the caller's try-with-resources.

## Considered options

- Option A — Document the contract clearly, add a runtime assertion that
  rejects `autoCommit=true` with `OutboxConfigurationException`.
- Option B — Wrap the supplied connection with a no-op `close()` and a
  guard against `setAutoCommit(true)`. Forces every call site through
  the wrapper.
- Option C — Require a `TransactionTemplate`-style abstraction instead of
  a raw connection supplier.

## Decision outcome

Chosen option: **Option A**, because it preserves the minimal API surface,
matches what callers already write, and converts the failure mode from
silent to typed.

The contract, codified in the `ConnectionSupplier` Javadoc and enforced at
runtime by `JdbcOutbox`:

| Rule | Enforcement |
| --- | --- |
| The returned connection participates in the caller's transaction | Documentation only; not technically enforceable from the publisher side |
| `autoCommit == false` on every call | Runtime assertion; throws `OutboxConfigurationException` with a remediation message |
| `JdbcOutbox` never calls `close()` / `commit()` / `rollback()` | Code audit; no test could prove a negative, but the implementation never holds the connection beyond the publish call |
| Supplier may throw `SQLException`; the publisher translates it | Existing translation through the dialect |

`OutboxConfigurationException` (sealed, see ADR-0008) is the right type
for the autocommit case: an operator must intervene; retrying does not
help.

### Positive consequences

- A misconfigured Spring autoconfiguration or a missing
  `setAutoCommit(false)` in plain JDBC fails fast on the very first
  publish, with a message that names the fix.
- The contract is explicit in Javadoc; reviewers and IDE quick-help
  surface it without spelunking through code.
- Spring autoconfig already wires `DataSourceUtils.getConnection`; that
  path is unaffected.

### Negative consequences

- `getAutoCommit()` adds one JDBC round trip per publish. This is a
  cached driver-side flag in PostgreSQL's JDBC driver, so the cost is
  effectively zero, but it is non-zero in principle.
- Callers experimenting in a REPL with `autoCommit=true` will see the
  exception. Acceptable — that is exactly the silent failure we want
  to surface.

## Pros and cons of the options

### Option A — Document + runtime assert

- Good, because misconfiguration is loud and typed.
- Good, because the API surface is unchanged.
- Bad, because the "right connection" half of the contract still relies
  on the caller doing the right thing — the publisher cannot detect a
  fresh, untransacted connection masquerading as the right one.

### Option B — Wrap the connection

- Good, because the publisher can intercept misuse.
- Bad, because every call site allocates a wrapper per publish.
- Bad, because the wrapper would have to forward the entire `Connection`
  surface (~50 methods) and re-implement transaction state tracking.

### Option C — `TransactionTemplate`-style abstraction

- Good, because the abstraction enforces the transaction boundary by
  construction.
- Bad, because it forces a Spring-shaped programming model on
  non-Spring callers.
- Bad, because it complicates the public API for a marginal safety win
  over Option A.

## Links

- ADR-0008 — sealed `OutboxException` hierarchy (`OutboxConfigurationException`)
- ADR-0013 — `OutboxDialect` SPI and auto-detection
- ROADMAP item P0-5
