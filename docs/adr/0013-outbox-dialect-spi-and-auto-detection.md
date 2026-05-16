# ADR-0013: `OutboxDialect` SPI and auto-detection

- Status: accepted
- Date: 2026-05-16
- Deciders: @lobofoltran
- Tags: outbox-jdbc, spi, serviceloader, postgres

## Context and problem statement

`JdbcOutbox` was PostgreSQL-specific: it embedded `?::jsonb` in the INSERT,
called `setObject(i, instant, Types.TIMESTAMP_WITH_TIMEZONE)`, classified
`SQLException`s by SQLState class, and bound `UUID` values via the
PostgreSQL-friendly `setObject(i, uuid)` overload. Every PR that touched
those concerns had to keep them in sync; every future dialect (MySQL,
Oracle, SQL Server, …) would have to fork `JdbcOutbox` or wrap it in
unnatural abstractions.

ADR-0009 already separated the concerns at the API level: `Outbox` is not
service-loaded, but a future `OutboxDialectProvider` SPI inside `outbox-jdbc`
*is* the legitimate service-loading point. This ADR realizes that SPI.

## Decision drivers

- The publisher must be database-agnostic: every PG-specific decision lives
  behind a typed seam.
- Adding a new dialect must not require modifying `JdbcOutbox` or any other
  existing class.
- Discovery should be zero-config in the common case: the consumer adds
  one JAR (e.g. a hypothetical `outbox-jdbc-mysql`) and the publisher picks
  it up automatically. Explicit override must remain available for tests
  and unusual deployments.
- The contract must be small enough that a competent JDBC author can
  implement a new dialect in one afternoon.

## Considered options

- Option A — `OutboxDialect` SPI + `OutboxDialectProvider` factory,
  discovered via `ServiceLoader`, auto-detected on first publish from
  `DatabaseMetaData`.
- Option B — Compile-time dialect selection: a Maven property or a
  Spring property names the dialect class, the publisher reflects on
  it. No `ServiceLoader`.
- Option C — Strategy pattern with a registry: every dialect calls
  `OutboxDialects.register(provider)` from a static initializer.
  Effectively `ServiceLoader` reinvented, with worse failure modes
  (registration order depends on classloading).

## Decision outcome

Chosen option: **Option A**, because `ServiceLoader` is the standard JPMS
extension point, requires no static-initializer choreography, and lets
both Maven (`META-INF/services/...`) and modular (`provides ... with ...`)
consumers participate.

### SPI shape

Two interfaces and one record live in the exported `outbox-jdbc-spi`
package `io.github.lobofoltran.outbox.jdbc.spi`:

```java
public interface OutboxDialect {
    String insertSql(TableRef table);
    void bindId(PreparedStatement statement, int index, UUID id) throws SQLException;
    void bindHeaders(PreparedStatement statement, int index, String headersJson) throws SQLException;
    void bindTimestamp(PreparedStatement statement, int index, Instant value) throws SQLException;
    void bindOptionalString(PreparedStatement statement, int index, String value) throws SQLException;
    OutboxException translate(SQLException ex, String contextMessage);
    Set<DialectCapability> capabilities();
}

public interface OutboxDialectProvider {
    boolean supports(DatabaseMetaData metaData) throws SQLException;
    OutboxDialect create();
    default int priority() { return 0; }
}

public enum DialectCapability {
    UPSERT_ON_CONFLICT, NATIVE_JSON, NATIVE_UUID, TIMESTAMP_WITH_TIMEZONE, BATCH_INSERT
}

public record TableRef(String schema, String tableName) { /* identifier validation */ }
```

Bundled implementation: `PostgresDialect` + `PostgresDialectProvider`, in
the **internal** package `io.github.lobofoltran.outbox.jdbc.dialect.postgres`,
*not* exported by the module. Consumers must depend on the SPI, never on
the bundled dialect class.

### Auto-detection

`JdbcOutbox` resolves the dialect lazily, on first publish:

1. The builder's `.dialect(OutboxDialect)` opt-out wins if set.
2. Otherwise, the publisher opens its first connection, reads
   `connection.getMetaData()`, iterates every registered
   `OutboxDialectProvider`, keeps the highest-priority match, and creates
   the dialect.
3. The result is cached in a `volatile` field. Subsequent publishes hit
   the cache lock-free; concurrent first-publishers may each detect once,
   which is harmless because detection is deterministic for a given
   connection.
4. If no provider matches, `OutboxConfigurationException` is raised with
   the offending product name.

`PostgresDialectProvider` matches when
`metaData.getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgres")`,
covering vanilla PostgreSQL, Aurora PostgreSQL, RDS PostgreSQL, and
wire-compatible derivatives. Priority is `0`; third-party providers may
return a higher value to take precedence.

### Module wiring

```text
module io.github.lobofoltran.outbox.jdbc {
    exports io.github.lobofoltran.outbox.jdbc;
    exports io.github.lobofoltran.outbox.jdbc.spi;

    uses io.github.lobofoltran.outbox.jdbc.spi.OutboxDialectProvider;
    provides io.github.lobofoltran.outbox.jdbc.spi.OutboxDialectProvider with
        io.github.lobofoltran.outbox.jdbc.dialect.postgres.PostgresDialectProvider;
}
```

The non-modular `META-INF/services/...OutboxDialectProvider` file is
also shipped, so applications running `outbox-jdbc` on the classpath
(rather than the module path) still discover the bundled provider.

### "No PG tokens in `JdbcOutbox`" rule

A unit test (`JdbcOutboxBytecodePurityTest`) scans the compiled
`JdbcOutbox.class` for substrings `jsonb`, `Postgres`, `postgres`,
`ON CONFLICT`, and `io/github/lobofoltran/outbox/jdbc/dialect`. Any
match fails the build. This codifies the architectural rule against
regressions: future contributors cannot accidentally re-leak
PostgreSQL-specific syntax into the publisher.

### Positive consequences

- Adding a new dialect is a leaf change: drop a JAR with a provider on
  the classpath; the publisher picks it up.
- `JdbcOutbox` is one screen of code per concern (transaction
  enforcement, batch path, dialect resolution); each concern is
  independently testable.
- The bundled PostgreSQL dialect is hidden behind the SPI. Swapping it
  for a fork or a fix does not break consumers.
- ADR-0008's SQLState classification table now has an enforced home:
  the dialect's `translate(...)` method.

### Negative consequences

- Adds three public types to `outbox-jdbc`'s exported surface
  (`OutboxDialect`, `OutboxDialectProvider`, `DialectCapability`,
  `TableRef`). They are part of the SPI contract from now on; breaking
  changes need an ADR.
- Auto-detection costs one `getMetaData()` call per `JdbcOutbox`
  instance lifetime. Negligible, but non-zero.
- A misregistered third-party provider with a high `priority()` could
  win over `PostgresDialectProvider`. Acceptable: that is the explicit
  override mechanism, and the consumer asked for it by adding the JAR.

## Pros and cons of the options

### Option A — `ServiceLoader`-discovered dialect SPI

- Good, because it is the standard JPMS extension point.
- Good, because zero-config in the happy path.
- Bad, because the SPI surface is now part of the public contract and
  has to evolve carefully.

### Option B — Compile-time dialect selection

- Good, because the dialect choice is explicit.
- Bad, because every consumer must remember to set the property; the
  common case (one JAR, one dialect) does not benefit from defaults.
- Bad, because reflection and string-named classes are a step backward
  from JPMS-aware service loading.

### Option C — Static-initializer registry

- Good, because no `ServiceLoader` machinery is involved.
- Bad, because registration order depends on classloading, which is
  notoriously fragile across application servers and test runners.
- Bad, because reasoning about which dialects are registered requires
  reading every dialect's bytecode.

## Links

- ADR-0008 — sealed `OutboxException` hierarchy (defines the SQLState
  classification this SPI's `translate(...)` honors)
- ADR-0009 — `ServiceLoader` is for dialect providers, not for `Outbox`
- ADR-0010 — `ConnectionSupplier` contract
- ADR-0011 — idempotent publish on duplicate id (lives in the dialect's
  `insertSql`)
- ADR-0012 — `publishAll` batch contract (uses the dialect's bindings)
- ROADMAP items P0-1 (binding part), P0-5, P0-6, P0-7
