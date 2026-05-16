# ADR-0016: TCK contract surface for `OutboxDialect` implementations

- Status: accepted
- Date: 2026-05-16
- Deciders: @lobofoltran
- Tags: outbox-tck, outbox-jdbc, spi, tests

## Context and problem statement

ADR-0013 introduced the `OutboxDialect` SPI so non-PostgreSQL databases can plug
into `outbox-jdbc` without forking. The SPI is small but its **runtime
contract** is rich: idempotent inserts, autocommit refusal, `Instant` timezone
round-trip, SQLState-to-typed-exception classification, batch atomicity, and
nullability rules. A new dialect that compiles is not a dialect that works —
the contract is what matters.

We need a way for external implementers to prove their dialect satisfies the
contract before they ship, without copy-pasting tests out of `outbox-jdbc`.

## Decision drivers

- An external dialect author (e.g. a hypothetical `outbox-jdbc-mysql`) must be
  able to validate their implementation in one afternoon.
- The contract base must run against the implementer's own database — this
  rules out fixed-fixture mocking.
- The TCK must encode capability gating: a dialect that does not advertise
  `UPSERT_ON_CONFLICT` should not be penalized; the documented fallback must be
  asserted instead.
- The TCK must ship as a **regular** dependency (no `classifier=tests`) so it
  resolves transitively through the BOM and behaves identically across Maven
  and Gradle consumers.
- The reference dialect (`PostgresDialect`) must pass 100% of the contract
  inside `outbox-jdbc`'s own `verify` build, so a regression in the publisher
  hot path fails CI immediately.

## Considered options

- Option A — Ship `outbox-tck` as a separate module with the abstract base
  class in `src/main/java`. External authors `extends
  OutboxDialectContractTest`, wire their dialect + `DataSource` + DDL, and
  inherit the suite as ordinary `@Test` methods.
- Option B — Ship the same base class via the `tests` classifier of
  `outbox-jdbc` (`<classifier>tests</classifier>`). One artifact, no new
  module.
- Option C — Don't ship a TCK; document the contract in prose and rely on
  external authors writing their own tests.

## Decision outcome

Chosen option: **Option A** — a dedicated `outbox-tck` module with the
abstract base in `src/main/java`.

A `tests`-classifier artifact (Option B) would technically work but has known
sharp edges: Maven resolves classified jars with caveats around transitive
dependencies (`type=test-jar` requires manual scope plumbing in every
consuming POM), Gradle treats them as a non-default capability, and the
`tests`-jar contents are conventionally treated as private. Shipping the
abstract class through `src/main/java` makes it part of the module's
documented public API — exactly what we want for a contract.

Option C is rejected because prose contracts decay; an executable contract is
the only one that gets maintained.

### Module shape

```
outbox-tck/
└── src/main/java/
    ├── module-info.java
    └── io/github/lobofoltran/outbox/tck/
        └── OutboxDialectContractTest.java     (abstract, JUnit 5)
```

The module exports `io.github.lobofoltran.outbox.tck` and `requires
transitive` `outbox-core`, `outbox-jdbc`, `java.sql`, JUnit Jupiter API, and
AssertJ so external authors do not need to re-declare the contract surface.
JUnit and AssertJ are intentionally **not** scope=`test` in this module — they
are part of the TCK's public API.

Testcontainers is shipped as a `compile`-scope Maven dependency for authoring
convenience but is intentionally **not** declared as a JPMS `requires`. The
contract base accepts a `javax.sql.DataSource` — no Testcontainers type
appears in any of its method signatures, fields, or thrown types — so a
JPMS-modular consumer that uses a non-Testcontainers `DataSource` (e.g. an
embedded H2, a service-managed Postgres) does not need Testcontainers at all.
Adding `requires transitive testcontainers` would also (a) lock every
consumer module to the brittle, filename-derived module name `testcontainers`
(the JAR has no `Automatic-Module-Name` manifest entry) and (b) force the
test-compile of subclassers to additionally `requires postgresql` for the
`org.testcontainers:postgresql` companion JAR, which is a separate module.
Subclassers declare `requires testcontainers` themselves in their own
`module-info.java`, exactly the way `PostgresDialectContractIT` does.

A smoke test (`TckModuleInfoTest`) pins the `requires transitive` set above
using the public `java.lang.module.ModuleDescriptor` API, so a future edit
that drops the `transitive` modifier or removes a required edge fails CI.

The reference implementation lives in `outbox-tck/src/test/java`:

```
outbox-tck/src/test/java/.../tck/PostgresDialectContractIT.java
```

It extends `OutboxDialectContractTest`, resolves `PostgresDialect` via
`ServiceLoader`, wires a Testcontainers PG `DataSource`, and applies
`outbox-publisher.sql`. Co-locating the reference IT with the TCK (rather
than under `outbox-jdbc/src/test/java`) avoids the reactor cycle that would
arise from `outbox-jdbc` depending test-scope on `outbox-tck`. The IT runs
in `verify` and must pass 100% of the contract.

## Contract surface

The table below enumerates every test method in `OutboxDialectContractTest`,
the {@link io.github.lobofoltran.outbox.jdbc.spi.DialectCapability} it depends
on (if any), and the documented fallback when that capability is absent.

| # | Test method | Capability gate | Expected outcome | Fallback (capability absent) |
| -- | --- | --- | --- | --- |
| 1 | `publish_persists_a_minimal_event` | — | One row written; required columns match the event. | n/a (always required) |
| 2 | `publish_persists_a_maximal_event` | — | All optional fields (`headers`, `destination`) round-trip through the dialect's bindings. | n/a (always required) |
| 3 | `publish_rejects_a_null_event` | — | `NullPointerException` from the API contract before any DB call. | n/a (publisher-level guarantee) |
| 4 | `timezone_round_trip_preserves_instant` | `TIMESTAMP_WITH_TIMEZONE` (implicit) | Writer JVM in `America/Sao_Paulo`, reader JVM in `UTC` — `Instant` equality preserved. | A dialect without TZ-aware columns must document the expected drift. The TCK does not currently enforce a fallback test; flagged for a follow-up ADR if a non-TZ dialect lands. |
| 5 | `publish_is_idempotent_on_duplicate_id_when_upsert_capability_is_advertised` | `UPSERT_ON_CONFLICT` (gate via `assumeTrue`) | Publishing the same `id` twice yields exactly one row, no exception. | Skipped via `Assumptions`; paired with #6. |
| 6 | `publish_throws_integrity_exception_on_duplicate_id_when_upsert_is_absent` | `UPSERT_ON_CONFLICT` (gate via `assumeFalse`) | Second publish raises `OutboxIntegrityException`; caller is expected to catch-and-swallow at the call site to recover idempotency semantics. | Skipped via `Assumptions`; paired with #5. |
| 7 | `translates_unique_violation_to_integrity_exception` | — | SQLState `23505` → `OutboxIntegrityException`. | n/a (always required by ADR-0008) |
| 8 | `translates_serialization_failure_to_transient_exception` | — | SQLState `40001` → `OutboxTransientException`. | n/a (always required by ADR-0008) |
| 9 | `translates_string_too_long_to_data_exception` | — | SQLState `22001` → `OutboxDataException`. | n/a (always required by ADR-0008) |
| 10 | `translates_undefined_column_to_configuration_exception` | — | SQLState `42703` → `OutboxConfigurationException`. | n/a (always required by ADR-0008) |
| 11 | `publish_rejects_an_autocommit_connection` | — | `OutboxConfigurationException` with message containing `autocommit`. | n/a (publisher-level guarantee, ADR-0010) |
| 12 | `publish_all_persists_every_event_in_the_batch` | `BATCH_INSERT` (implicit through `JdbcOutbox.publishAll`) | Every event in the batch is persisted; row count matches input size. | A dialect without batch support inherits the default `Outbox.publishAll` loop and still passes — the TCK does not assert one-round-trip, only the final row count. |
| 13 | `publish_all_rolls_back_with_the_callers_transaction` | — | A `connection.rollback()` after `publishAll` removes every event in the batch. Demonstrates atomicity with the caller's TX (ADR-0012). | n/a (always required) |

### Capability matrix used by the TCK

| Capability | Used by | Notes |
| --- | --- | --- |
| `UPSERT_ON_CONFLICT` | #5 (gate true) / #6 (gate false) | Strict pairing — exactly one of the two runs. |
| `TIMESTAMP_WITH_TIMEZONE` | #4 (implicit) | Currently unconditional; will become an `assumeTrue` gate the day a non-TZ dialect lands. |
| `BATCH_INSERT` | #12 (implicit) | Currently unconditional; the test asserts effect, not implementation, so it passes for both batch-capable and loop-fallback dialects. |
| `NATIVE_JSON` | not yet gated | Headers are bound through `OutboxDialect#bindHeaders`; a dialect without `NATIVE_JSON` is expected to bind as text. The TCK trusts the dialect's binding decision and asserts only that the round-tripped value parses back equal — there is no SQL-level capability gate today. |
| `NATIVE_UUID` | not yet gated | Same shape as `NATIVE_JSON`: tested through `bindId` indirection. |

The `not yet gated` rows are deliberate: every bundled and reasonably likely
dialect supports those capabilities, and adding a gate that no dialect ever
takes the false branch of would be dead test infrastructure. The matrix is
the explicit ledger of "where the next gate goes" for future dialects.

### Positive consequences

- A new dialect proves itself by passing one extension class — no copy-paste.
- The contract evolves as code, in `OutboxDialectContractTest`, with a
  changelog entry and an ADR amendment per change.
- `PostgresDialect` is held to the same bar as any future dialect; if a
  refactor breaks the contract, `PostgresDialectContractIT` fails first.

### Negative consequences

- Adds a new module (`outbox-tck`) and the maintenance overhead that comes
  with it.
- The contract base lives in `src/main/java`, which means JaCoCo of
  `outbox-tck` sees a class with `@Test`-annotated methods that are never
  executed in this module's own build. We override the module's coverage
  thresholds to `0.00` with a documented justification.
- The TCK's transitive dependencies (JUnit 5, AssertJ, Testcontainers) leak
  into consumer test classpaths. This is intentional — and consumers can
  exclude what they don't need — but the transitive surface is now part of
  the BOM.

## Pros and cons of the options

### Option A — Dedicated `outbox-tck` module, base in `src/main/java`

- Good, because the base class is part of a documented public API and
  versions with the rest of the BOM.
- Good, because Maven and Gradle resolution semantics are identical to any
  other dependency.
- Bad, because it adds a module to maintain.

### Option B — `tests` classifier of `outbox-jdbc`

- Good, because no new module.
- Bad, because `type=test-jar` resolution requires hand-wired scope plumbing
  in every consumer; transitive `dependencyManagement` of test-classified
  artifacts is a known sharp edge.
- Bad, because the contents of a `tests` jar are conventionally implementation
  detail of the producing module.

### Option C — Prose contract only

- Good, because zero artifacts.
- Bad, because the contract decays the moment two implementations interpret a
  sentence differently. Executable contracts force consensus.

## Links

- ADR-0008 — sealed `OutboxException` hierarchy (defines the SQLState
  classification the TCK asserts)
- ADR-0010 — `ConnectionSupplier` contract (autocommit refusal test)
- ADR-0011 — idempotent publish on duplicate id (capability-gated tests)
- ADR-0012 — `publishAll` batch contract
- ADR-0013 — `OutboxDialect` SPI (the contract this TCK is for)
