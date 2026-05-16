# ADR-0018: Close the sealed `OutboxException` hierarchy with `OutboxValidationException`

- Status: accepted
- Date: 2026-05-16
- Deciders: @lobofoltran
- Tags: outbox-core, error-handling, api, breaking-change

## Context and problem statement

[ADR-0008](0008-sealed-outbox-exception-hierarchy.md) introduced a sealed
`OutboxException` with four leaf subtypes so callers could `catch` typed
branches without parsing SQLState codes. The release notes for 0.2.0
advertised the hierarchy as "the way" to handle every error coming out of the
library.

DEBT-08 — surfaced while reviewing the 0.3.0 sample — showed that the story
was only partially true:

- `OutboxEvent`'s compact constructor and `Builder` reject `null` required
  fields with `NullPointerException` (via `Objects.requireNonNull`) and blank
  strings with `IllegalArgumentException`. Neither is part of the sealed
  hierarchy.
- `PostgresDialect.validate(OutboxEvent)` (added in 0.3.0 when column-width
  validation moved out of the record) also throws `IllegalArgumentException`
  for oversize values, *before* any SQL is sent. Same gap.
- `JdbcOutbox`, `MeteredOutbox`, and `TracedOutbox` all guard `publish(null)`
  and `publishAll(null)` with `Objects.requireNonNull`, so the entry to the
  publish path itself is not covered by `OutboxException`.

Net effect: a caller cannot write a single `catch (OutboxException e)` around
the natural `builder()...build()` + `publish(event)` sequence and trust it
covers every failure. The 0.3.0 sample app demonstrates this by catching
`NullPointerException` explicitly. That undermines the entire reason for the
sealed hierarchy.

## Decision drivers

- The sealed-hierarchy promise from ADR-0008 must be genuinely closed: every
  failure originating in the library must be an `OutboxException` subtype.
- A caller-side programming bug (null/blank required field) is semantically
  distinct from a DB-side data rejection (column width, SQLState 22) — they
  share "abort, do not retry" but originate at different layers.
- Existing consumers that rely on `catch (OutboxException e)` must keep
  working. Existing consumers that explicitly catch `NullPointerException` /
  `IllegalArgumentException` from the builder are accepted breakage on the
  0.x line.
- No exhaustive `switch` on `OutboxException` exists in production code
  today, so adding a fifth permit does not silently make a `default` branch
  unreachable.

## Considered options

- **Option A — Add `OutboxValidationException` as a fifth permit.** Builder
  and compact constructor throw it for null/blank arguments; `JdbcOutbox`,
  `MeteredOutbox`, `TracedOutbox` throw it for null `event` / `events`. The
  dialect's column-width check moves from `IllegalArgumentException` to
  `OutboxDataException` (data is malformed for the underlying store).
- **Option B — Reuse `OutboxDataException` for everything.** No new permit.
  Conflates "caller passed bad arguments" with "DB rejected the data".
- **Option C — Reuse `OutboxConfigurationException`.** The mapping suggested
  by the original debt note. Semantically wrong — configuration is about
  deploy/schema/connectivity, not bad input.
- **Option D — Keep the JDK exceptions, document the gap.** Honest but
  defeats the point of the sealed hierarchy and forces every consumer to
  write two catch blocks.

## Decision outcome

Chosen option: **Option A — add `OutboxValidationException`**, because it
preserves the typed-branch story (the caller can still distinguish "bad
argument" from "DB malformed-data error"), keeps `OutboxDataException`'s
semantics intact, and the new permit lands without breaking any production
exhaustive `switch` in this repo.

Concretely:

- A new `OutboxValidationException` joins `permits` on `OutboxException`.
- `OutboxEvent.Builder` setters and the compact constructor throw it for
  `null` required arguments, blank strings, and null header keys/values.
- `Outbox.publish(null)` and `Outbox.publishAll(null)` — both the default
  method and every implementation in this repo — throw it.
- `OutboxDialect.validate(OutboxEvent)` (default in the SPI, override in
  `PostgresDialect`) is documented and implemented to throw
  `OutboxDataException` when a column-width invariant is violated. This is a
  data-malformed-for-store condition; the row would have been rejected by
  the DB anyway, the dialect just spots it one round-trip earlier.
- `JdbcOutbox.Builder` and `MeteredOutbox` / `TracedOutbox` constructors
  keep their existing `NullPointerException` for misuse of *their own*
  builder/constructor APIs. These are wiring-time errors at composition,
  not a publish-path concern, and they do not run inside the
  `builder()...build()` + `publish(event)` sequence that the sealed
  hierarchy is supposed to cover.

### Positive consequences

- A single `catch (OutboxException e)` around the publish path is now
  sufficient. The 0.3.0 sample's `catch (NullPointerException ... )`
  block can go away in 0.4.0+.
- Validation errors carry a typed branch distinct from DB-side data
  rejections, so callers that *do* want to differentiate (e.g. report
  bad-input bugs separately from upstream-data-quality bugs) can.
- The dialect's pre-flight width check now surfaces with the same type as
  the SQLState-22 path it shortcuts, so callers see a stable category
  regardless of whether the check fires upfront or after the INSERT.

### Negative consequences

- **Breaking change** for code that explicitly catches
  `NullPointerException` or `IllegalArgumentException` from the builder, the
  compact constructor, or `dialect.validate(...)`. Acceptable on the 0.x
  line; called out in the CHANGELOG. Bump to 0.4.0.
- One more leaf subtype to remember when writing exhaustive `switch`. No
  existing production switch is affected.
- The dialect's column-width check no longer matches the JDK convention
  ("invalid argument → `IllegalArgumentException`"). The trade-off is
  deliberate: alignment with the sealed hierarchy is more valuable here
  than alignment with the JDK convention.

## Pros and cons of the options

### Option A — Add `OutboxValidationException`

- Good, because the sealed hierarchy is now genuinely closed.
- Good, because "bad argument" stays distinct from "DB rejected data".
- Bad, because it is a breaking change for catch-NPE / catch-IAE callers.

### Option B — Reuse `OutboxDataException`

- Good, because no new permit.
- Bad, because it conflates two different originators (caller argument vs.
  DB rejection) and the Javadoc on `OutboxDataException` would have to
  awkwardly describe both.

### Option C — Reuse `OutboxConfigurationException`

- Good, because no new permit.
- Bad, because "missing table, wrong driver" has nothing to do with "null
  aggregateType in a single call". Fail-fast advice differs too.

### Option D — Document the gap

- Good, because zero code changes and zero breaking changes.
- Bad, because every consumer needs two catch blocks and the sealed
  hierarchy stops being a useful affordance.

## Links

- [ADR-0008](0008-sealed-outbox-exception-hierarchy.md) — the original
  sealed hierarchy this ADR closes.
- DEBT-08 (internal tech-debt log) — the gap this ADR resolves.
