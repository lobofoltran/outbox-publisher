# outbox-publisher

A Java library that gives your application a single API — `outbox.publish(event)` — to write events to an `outbox` table inside the caller's database transaction (Transactional Outbox pattern).

The application **does not know** how those events will be delivered to the outside world. That responsibility belongs to a relay (polling-based, see [`outbox-relay`](#related-projects)) or to a CDC pipeline like Debezium. The boundary between this library and any consumer is the **`outbox` table** — there is no Java coupling between this project and the relay.

API reference: the aggregated Javadoc for the latest release is published at <https://lobofoltran.github.io/outbox-publisher/>.

## Table of contents

- [Why this library](#why-this-library)
- [What it is not](#what-it-is-not)
- [Architecture](#architecture)
- [Installation](#installation)
- [Quick start (Spring Boot)](#quick-start-spring-boot)
- [Quick start (plain JDBC)](#quick-start-plain-jdbc)
- [Try it out](#try-it-out)
- [The `outbox` table contract](#the-outbox-table-contract)
- [Module-by-module reference](#module-by-module-reference)
- [Multi-module / hexagonal projects](#multi-module--hexagonal-projects)
- [Configuration reference](#configuration-reference)
- [Metrics](#metrics)
- [Tracing](#tracing)
- [Operating model: how events get delivered](#operating-model-how-events-get-delivered)
- [Compatibility matrix](#compatibility-matrix)
- [Build, test, release](#build-test-release)
- [FAQ](#faq)
- [Related projects](#related-projects)
- [License](#license)

## Why this library

If your application publishes events to Kafka/RabbitMQ/SNS as a side effect of a database change, you have a dual-write problem: the DB commit and the broker publish are two independent transactions. Crashes between them cause lost or duplicated messages.

The **Transactional Outbox** pattern solves this by writing the event into a local table inside the same transaction as the business change. A separate process reads that table and forwards events to the broker.

`outbox-publisher` provides only the **first half**: a small, opinionated API to write to the outbox table — nothing more.

Key design decisions:

- **No broker dependency in the application's classpath.** Your business code never imports Kafka or RabbitMQ.
- **JPMS-modular.** Internals are not visible to consumers; the compiler enforces it.
- **No bundled migrations.** We ship example SQL only. You apply it through whatever migration tool your microservice already uses (Flyway, Liquibase, hand-rolled).

## What it is not

- Not a scheduler.
- Not a message broker.
- Not a relay. It does not poll, retry, or deliver. See [`outbox-relay`](#related-projects).
- Not a serialization framework. `payload` is `byte[]`. You decide JSON, Avro, Protobuf, etc.
- Not opinionated about ordering or partitioning — those are relay/broker concerns.

## Architecture

```
┌────────────────────────────────────────────────────────────┐
│ YOUR APPLICATION                                           │
│                                                            │
│   @Inject Outbox outbox;                                   │
│   outbox.publish(event);     ◄── ONLY API surface          │
└─────────────────────────┬──────────────────────────────────┘
                          │  requires io.github.lobofoltran.outbox.core
                          ▼
┌────────────────────────────────────────────────────────────┐
│ outbox-core    (interface Outbox, record OutboxEvent)      │
└─────────────────────────▲──────────────────────────────────┘
                          │ implements Outbox
                          │
┌────────────────────────────────────────────────────────────┐
│ outbox-jdbc    (JdbcOutbox — INSERT in the caller's TX)    │
└─────────────────────────┬──────────────────────────────────┘
                          │ JDBC
                          ▼
                  ┌──────────────────┐
                  │  outbox  table   │  ◄── public contract
                  └──────────────────┘
                          ▲
                          │ SELECT ... FOR UPDATE SKIP LOCKED
                          │
                  (consumed by a relay or by CDC — out of scope)
```

The library ships eight Maven modules:

| Module | Purpose | Application sees it? |
| --- | --- | --- |
| `outbox-core` | `Outbox` interface, `OutboxEvent` record, exceptions | Yes (compile) |
| `outbox-jdbc` | JDBC implementation of `Outbox` | No (runtime) |
| `outbox-spring` | Spring Boot autoconfiguration | No (runtime) |
| `outbox-micrometer` | `MeteredOutbox` decorator — Micrometer Timer/Counter on every publish | No (runtime, optional) |
| `outbox-otel` | `TracedOutbox` decorator — OpenTelemetry `messaging.*` span on every publish | No (runtime, optional) |
| `outbox-schema` | Example SQL DDL as classpath resources | No (optional) |
| `outbox-tck` | Technology Compatibility Kit — abstract JUnit 5 contract base for `OutboxDialect` implementations | No (test, only when authoring a new dialect) |
| `outbox-bom` | Bill of Materials for version management | imported in BOM |

## Installation

### 1. Configure the GitHub Packages repository

Artifacts are published to GitHub Packages, which requires authentication even for public packages. **Never paste a raw PAT into `~/.m2/settings.xml`** — that file is read by every Maven invocation and is a frequent exfiltration target (malicious post-install hooks, shared dev machines, accidental dotfile commits). Use one of the two options below.

#### Option A (recommended) — environment variables sourced from a vault

Reference environment variables from `settings.xml` so the file itself contains no secret:

```xml
<settings>
  <servers>
    <server>
      <id>github-lobofoltran</id>
      <username>${env.GITHUB_USERNAME}</username>
      <password>${env.GITHUB_TOKEN}</password>
    </server>
  </servers>
</settings>
```

Then export the variables from a vault-backed shell init (1Password CLI, `pass`, `gh auth token`, etc.). For example, if you already authenticate with `gh`:

```sh
export GITHUB_USERNAME="your-github-handle"
export GITHUB_TOKEN="$(gh auth token)"   # PAT with read:packages
```

Tighten the file permissions for hygiene even though no secret lives there:

```sh
chmod 600 ~/.m2/settings.xml
```

#### Option B — Maven master password encryption

If you cannot rely on a vault-backed shell, encrypt the PAT with Maven's master-password mechanism. Create a master password and store it in `~/.m2/settings-security.xml` (mode `0600`), then encrypt the PAT and paste only the cipher text into `settings.xml`:

```sh
mvn --encrypt-master-password        # → write to ~/.m2/settings-security.xml
mvn --encrypt-password                # → paste cipher text as <password> in settings.xml
chmod 600 ~/.m2/settings.xml ~/.m2/settings-security.xml
```

See the [Maven password encryption guide](https://maven.apache.org/guides/mini/guide-encryption.html) for the exact file format.

#### CI

CI uses the runner-provided `GITHUB_TOKEN` injected as an environment variable; this project's workflows never write `settings.xml` to disk. Do not replicate the contributor setup on a runner.

Then in your project's `pom.xml`:

```xml
<repositories>
    <repository>
        <id>github-lobofoltran</id>
        <url>https://maven.pkg.github.com/lobofoltran/outbox-publisher</url>
        <snapshots><enabled>false</enabled></snapshots>
    </repository>
</repositories>
```

Only releases are published — no SNAPSHOTs.

### 2. Import the BOM

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.github.lobofoltran</groupId>
            <artifactId>outbox-bom</artifactId>
            <version>${outbox.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

### 3. Pick your integration

**Spring Boot users — recommended:**

```xml
<dependencies>
    <!-- Compile: the API your code references -->
    <dependency>
        <groupId>io.github.lobofoltran</groupId>
        <artifactId>outbox-core</artifactId>
    </dependency>

    <!-- Compile: Spring Boot autoconfiguration -->
    <dependency>
        <groupId>io.github.lobofoltran</groupId>
        <artifactId>outbox-spring</artifactId>
    </dependency>

    <!-- Runtime: JDBC implementation (no compile-time visibility) -->
    <dependency>
        <groupId>io.github.lobofoltran</groupId>
        <artifactId>outbox-jdbc</artifactId>
        <scope>runtime</scope>
    </dependency>
</dependencies>
```

**Plain JDBC (no Spring):**

```xml
<dependencies>
    <dependency>
        <groupId>io.github.lobofoltran</groupId>
        <artifactId>outbox-core</artifactId>
    </dependency>
    <dependency>
        <groupId>io.github.lobofoltran</groupId>
        <artifactId>outbox-jdbc</artifactId>
    </dependency>
</dependencies>
```

### 4. Apply the SQL

The `outbox-schema` module ships **two** SQL files as classpath resources. Copy them into your migration tool of choice (Flyway, Liquibase, hand-rolled — your call):

```
outbox-schema/src/main/resources/sql/postgres/
├── outbox-publisher.sql          (mandatory)
└── outbox-relay-extension.sql    (optional — only for the polling relay)
```

The schema is deliberately split by responsibility:

| Adoption mode | Apply | When to choose |
| --- | --- | --- |
| **Polling relay** | `outbox-publisher.sql` then `outbox-relay-extension.sql` | You run [`outbox-relay`](#related-projects) (or your own poller) inside the same database. |
| **CDC (Debezium)** | `outbox-publisher.sql` only | You stream the WAL via Debezium and route events with the Outbox Event Router SMT. The relay-only columns and partial indexes would be pure overhead. |
| **Hybrid / unsure** | `outbox-publisher.sql` only, today | Start CDC-shaped. The relay extension is idempotent (`ADD COLUMN IF NOT EXISTS`, `CREATE INDEX IF NOT EXISTS`) and can be applied later without data migration. |

This library will **never** create or alter the table automatically. That is your migration tool's job.

## Quick start (Spring Boot)

```java
import io.github.lobofoltran.outbox.OutboxEvent;
import io.github.lobofoltran.outbox.Outbox;

@Service
class OrderService {

    private final OrderRepository repository;
    private final Outbox outbox;

    @Transactional
    public void place(Order order) {
        repository.save(order);

        outbox.publish(OutboxEvent.builder()
            .aggregateType("Order")
            .aggregateId(order.id().toString())
            .eventType("OrderPlaced")
            .destination("orders.events")
            .contentType("application/json")
            .payload(orderJsonBytes(order))
            .header("tenant-id", order.tenantId())
            .build());
    }
}
```

What happens on commit:

1. `repository.save(order)` writes the order rows.
2. `outbox.publish(...)` writes a row into `outbox` in the **same** transaction.
3. The Spring transaction commits both atomically.
4. A relay (separate process or scheduled bean elsewhere) eventually picks it up and forwards to the broker. **That is out of scope for this library.**

If the commit fails, the outbox row is rolled back together with the business change. No dual-write, no lost events.

> **Spring Boot 4 + Flyway:** Spring Boot 4 no longer auto-configures Flyway from the `flyway-core` JAR alone. Add `org.springframework.boot:spring-boot-starter-flyway` (and `org.flywaydb:flyway-database-postgresql`) to wire migrations correctly. The runnable [`examples/spring-boot-quickstart`](examples/spring-boot-quickstart/) project documents the full setup, including `~/.m2/settings.xml` configuration for the GitHub Packages auth (use a unique repository id like `lobofoltran-outbox` — reusing the generic `id=github` collides with other GH Packages servers).

## Quick start (plain JDBC)

When you do not use Spring, build the `Outbox` yourself and supply a `Connection` provider. The provider must return the connection that participates in the caller's current transaction.

```java
import io.github.lobofoltran.outbox.Outbox;
import io.github.lobofoltran.outbox.jdbc.JdbcOutbox;

DataSource ds = ...; // your pooled DataSource

Outbox outbox = JdbcOutbox.builder()
    .connectionSupplier(() -> currentTxConnection(ds))
    .build();

try (Connection conn = ds.getConnection()) {
    conn.setAutoCommit(false);
    // ... your business writes ...
    outbox.publish(event);
    conn.commit();
}
```

## Try it out

Two end-to-end runnable examples live under [`examples/`](examples/):

- [`examples/spring-boot-quickstart`](examples/spring-boot-quickstart/) — Spring Boot 4 with autoconfigured `Outbox`, Flyway-applied schema, and a Testcontainers integration test. The README also covers the Spring Boot 4 + Flyway gotcha and GitHub Packages auth setup.
- [`examples/plain-jdbc`](examples/plain-jdbc/) — pure JDBC + HikariCP, manual transaction boundary, dialect auto-detection, with rollback semantics demonstrated by a Testcontainers test.

```bash
# Install the reactor first so the BOM resolves locally
./mvnw -B -ntp -DskipTests install

# Then build any example against the installed BOM
mvn -B -ntp -f examples/spring-boot-quickstart/pom.xml verify
mvn -B -ntp -f examples/plain-jdbc/pom.xml verify
```

The examples are intentionally **not** part of the Maven reactor — they are documentation and CI fixtures, not released artifacts.

## The `outbox` table contract

This is the **only** integration surface between `outbox-publisher` and any consumer. The DDL is shipped by `outbox-schema` as two PostgreSQL files — apply the publisher script always, the relay extension only when you run a polling relay (see [Apply the SQL](#4-apply-the-sql)).

### `outbox-publisher.sql` — mandatory

```sql
CREATE TABLE outbox (
    id              UUID         PRIMARY KEY,
    aggregate_type  VARCHAR(128) NOT NULL,
    aggregate_id    VARCHAR(128) NOT NULL,
    event_type      VARCHAR(128) NOT NULL,
    payload         BYTEA        NOT NULL,
    content_type    VARCHAR(64)  NOT NULL,
    headers         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    destination     VARCHAR(128),
    occurred_at     TIMESTAMPTZ  NOT NULL,
    schema_version  SMALLINT     NOT NULL DEFAULT 1
);
```

`occurred_at` is `TIMESTAMPTZ` (timestamp with time zone) so two JVMs in different time zones writing the same `Instant` produce the same row.

### `outbox-relay-extension.sql` — optional, polling relay only

```sql
ALTER TABLE outbox
    ADD COLUMN IF NOT EXISTS status          VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN IF NOT EXISTS attempts        INT         NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS published_at    TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_error      TEXT;

CREATE INDEX IF NOT EXISTS idx_outbox_pending
    ON outbox (next_attempt_at, occurred_at)
    WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_outbox_sent
    ON outbox (published_at)
    WHERE status = 'SENT';
```

The script is idempotent so it can be layered on top of an existing publisher table at any time without data migration — useful when a deployment that started CDC-only later adds a polling relay.

### Indexes and the two query patterns

The schema is designed knowing that consumers will run two distinct query patterns against this table:

| Pattern | Who runs it | Query shape | Index |
| --- | --- | --- | --- |
| Pending fetch (poll) | Relay (hot path) | `WHERE status='PENDING' AND (next_attempt_at IS NULL OR …) ORDER BY occurred_at … FOR UPDATE SKIP LOCKED` | `idx_outbox_pending` |
| Retention purge | Scheduled cleanup | `DELETE WHERE status='SENT' AND published_at < :cutoff` | `idx_outbox_sent` |

Both indexes are **partial** — they index only the rows in the relevant status, keeping them small even when the table grows large. `idx_outbox_pending` deliberately omits `status` from the key (already pinned by the `WHERE`), and orders by `(next_attempt_at, occurred_at)` so the planner can both skip backed-off rows and serve the `ORDER BY` without a sort step.

Ad-hoc debugging queries (e.g. by `aggregate_type`/`aggregate_id`) are intentionally not indexed — add them in your migrations if you need them, but they are not part of the contract.

| Column | Written by publisher? | Written by relay? | Notes |
| --- | :---: | :---: | --- |
| `id` | yes | no | UUIDv7 recommended for time-ordered keys. |
| `aggregate_type` | yes | no | e.g. `Order`, `Customer`. |
| `aggregate_id` | yes | no | Stringified domain id. |
| `event_type` | yes | no | e.g. `OrderPlaced`. |
| `payload` | yes | no | Opaque bytes. Library does not interpret. |
| `content_type` | yes | no | e.g. `application/json`, `application/avro`. |
| `headers` | yes | no | `Map<String,String>` serialized as JSON. |
| `destination` | yes (optional) | no | Hint for the relay: topic/exchange/queue. |
| `occurred_at` | yes | no | Domain timestamp (not insertion time). |
| `status` | default `PENDING` | yes | `PENDING` → `SENT` / `FAILED`. |
| `attempts` | default `0` | yes | Incremented by the relay on each try. |
| `next_attempt_at` | null | yes | Backoff scheduling owned by the relay. |
| `published_at` | null | yes | Set when the broker accepts the message. |
| `last_error` | null | yes | Last failure reason. |
| `schema_version` | yes | no | Bumped on incompatible changes; default `1`. |

### Compatibility rules

- **Additive changes** (new nullable columns, new indexes) are allowed within a `schema_version` and do not break consumers.
- **Renames, type changes, removed columns** require:
  1. A new `schema_version` value, and
  2. Both writers and readers updated in a documented order.

## Module-by-module reference

### `outbox-core`

Pure Java, zero runtime dependencies beyond `java.base` and `org.slf4j`.

Public API:

```java
package io.github.lobofoltran.outbox;

public interface Outbox {
    void publish(OutboxEvent event);

    /** Default loops over publish; JDBC and Micrometer override with a true batch path. */
    default void publishAll(Iterable<OutboxEvent> events) { ... }
}

public record OutboxEvent(
    UUID id,
    String aggregateType,
    String aggregateId,
    String eventType,
    String contentType,
    byte[] payload,
    Map<String, String> headers,
    String destination,
    Instant occurredAt
) {
    public static Builder builder() { ... }

    /** Byte length of the payload — avoids cloning the array (used on the metrics hot path). */
    public int payloadSize() { ... }
}

// Sealed hierarchy. Catching OutboxException continues to work; new code can
// pattern-match on the subtype to distinguish retryable failures from terminal ones.
public sealed class OutboxException
        extends RuntimeException
        permits OutboxTransientException,        // retry the surrounding transaction
                OutboxIntegrityException,         // duplicate id — treat as idempotent success
                OutboxDataException,              // invalid payload / column data — do not retry
                OutboxConfigurationException { }  // schema / connectivity wrong — fail fast
```

JPMS:

```java
module io.github.lobofoltran.outbox.core {
    exports io.github.lobofoltran.outbox;
}
```

### `outbox-jdbc`

JDBC implementation. Internals are **not** exported.

```java
module io.github.lobofoltran.outbox.jdbc {
    requires transitive io.github.lobofoltran.outbox.core;
    requires transitive java.sql;
    requires org.slf4j;

    exports io.github.lobofoltran.outbox.jdbc;      // JdbcOutbox + ConnectionSupplier
    exports io.github.lobofoltran.outbox.jdbc.spi;  // OutboxDialect SPI

    uses io.github.lobofoltran.outbox.jdbc.spi.OutboxDialectProvider;
    provides io.github.lobofoltran.outbox.jdbc.spi.OutboxDialectProvider with
        io.github.lobofoltran.outbox.jdbc.dialect.postgres.PostgresDialectProvider;
}
```

Consumers obtain `Outbox` either through the Spring Boot autoconfiguration in `outbox-spring` or by directly invoking `JdbcOutbox.builder()...build()`. There is no `ServiceLoader` discovery of `Outbox` itself.

#### Dialect SPI

`outbox-jdbc` is database-agnostic; every PostgreSQL-specific decision (idempotent `INSERT ... ON CONFLICT`, `?::jsonb` cast, `TIMESTAMP WITH TIMEZONE` binding, SQLState classification) lives behind an `OutboxDialect` SPI in `io.github.lobofoltran.outbox.jdbc.spi`. The bundled `PostgresDialect` is auto-discovered through `ServiceLoader` on the first publish; pass `.dialect(...)` to the builder to pin one explicitly.

#### Writing a new dialect

External authors who want to plug a non-PostgreSQL database into `outbox-jdbc` validate their implementation against the `outbox-tck` Technology Compatibility Kit. The TCK ships an abstract JUnit 5 contract base class in its main JAR (no `classifier=tests`); extend it once, wire your dialect plus a real `DataSource`, and you inherit the full runtime contract — happy-path inserts, autocommit refusal, timezone round-trip, capability-gated idempotency, SQLState classification, and batch atomicity.

```xml
<dependency>
    <groupId>io.github.lobofoltran</groupId>
    <artifactId>outbox-tck</artifactId>
    <scope>test</scope>
</dependency>
```

```java
@Testcontainers
class MyDialectContractIT extends OutboxDialectContractTest {

    @Container
    static final MyDbContainer DB = new MyDbContainer("mydb:1.2");

    @Override protected OutboxDialect dialect() { return new MyDialect(); }
    @Override protected DataSource    dataSource() { return DB.dataSource(); }
    @Override protected void applyPublisherSchema(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) { s.execute(loadDdl()); }
    }
}
```

The canonical example is `PostgresDialectContractIT` inside `outbox-jdbc/src/test/java/.../dialect/postgres/` — it runs as part of this repository's `verify` build and `PostgresDialect` passes 100% of the contract.

#### Builder

```java
JdbcOutbox.builder()
    .connectionSupplier(() -> DataSourceUtils.getConnection(dataSource))
    .schema("public")          // optional
    .tableName("outbox")        // default: "outbox"
    .clock(Clock.systemUTC())   // default
    .idGenerator(uuidV7())      // default
    .dialect(myDialect)         // optional; auto-detected if unset
    .build();
```

The supplier MUST return a connection in `autoCommit=false` mode that participates in the caller's transaction. Misconfiguration surfaces as `OutboxConfigurationException` on the first publish.

When running under Spring Boot, prefer tweaking the auto-configured builder through a [`JdbcOutboxBuilderCustomizer`](#jdbcoutboxbuildercustomizer--extending-the-autoconfig-without-replacing-the-bean) bean instead of constructing a `JdbcOutbox` by hand — that path preserves the autoconfig's `Outbox` bean (and its observability decorators).

### `outbox-spring`

Spring Boot 4 autoconfiguration. Automatic Maven module (no `module-info.java`). It:

- Creates the `Outbox` bean wired to the application's `DataSource`.
- Hooks `JdbcOutbox` into Spring's transaction manager so the INSERT joins the current transaction.
- Reads `io.github.lobofoltran.outbox.*` properties (see [Configuration reference](#configuration-reference)).
- Wraps the resulting `Outbox` with `MeteredOutbox` (Micrometer) and/or `TracedOutbox` (OpenTelemetry) when the respective modules and beans are present. The wrappers are applied in a deterministic order — `TracedOutbox(MeteredOutbox(JdbcOutbox))` — via priority-ordered `BeanPostProcessor`s.
- Registers `OutboxHealthIndicator` when Spring Boot Actuator is on the classpath and `management.health.outbox.enabled` is `true` (default). The indicator runs a `SELECT 1 FROM <schema>.<table> WHERE 1=0` probe and reports `UP`/`DOWN` accordingly — see `outbox-spring/src/main/java/.../OutboxHealthIndicator.java`.

#### `JdbcOutboxBuilderCustomizer` — extending the autoconfig without replacing the bean

The recommended way to inject a custom `IdGenerator`, deterministic `Clock`, or explicit `OutboxDialect` is to register one or more `JdbcOutboxBuilderCustomizer` beans. They are invoked after `OutboxProperties` have been applied to the builder and before `build()` runs:

```java
@Bean
JdbcOutboxBuilderCustomizer ulidIdGenerator() {
    return builder -> builder.idGenerator(clock -> ulid().toUuid());
}

@Bean
JdbcOutboxBuilderCustomizer fixedClockForTests(Clock clock) {
    return builder -> builder.clock(clock);
}
```

The autoconfig backs off entirely if you register your own `@Bean Outbox`, so customizers are the preferred extension point for "use the auto-configured builder, but tweak X".

### `outbox-micrometer`

Optional decorator module. Adds a `MeteredOutbox` that wraps any `Outbox` and emits two meters per `publish` call: an `outbox.publish` `Timer` (tags `aggregate_type`, `event_type`, `result`) and an `outbox.publish.bytes` `DistributionSummary` (tags `aggregate_type`, `event_type`). `aggregate_id` and `destination` are deliberately never tagged so cardinality stays bounded. Micrometer itself is `<scope>provided</scope><optional>true</optional>` so the consumer's Micrometer BOM stays the single source of truth for version.

### `outbox-otel`

Optional decorator module. `TracedOutbox` wraps any `Outbox` and emits an OpenTelemetry `PRODUCER` span per publish, named `outbox publish` (single) or `outbox publish_batch` (batch), with attributes following the [messaging semantic conventions](https://opentelemetry.io/docs/specs/semconv/messaging/messaging-spans/) (`messaging.system=outbox`, `messaging.operation`, `messaging.message.id`, `messaging.destination.name`, `outbox.aggregate_type`, `outbox.event_type`, `messaging.batch.message_count`). On exception, span status is set to `ERROR` and the original exception is rethrown unchanged.

### `outbox-tck`

Technology Compatibility Kit for `OutboxDialect` implementations. Ships an abstract JUnit 5 contract base class — `OutboxDialectContractTest` — in its main JAR (no `classifier=tests`) so external dialect authors can extend it as a regular `test`-scoped dependency. The contract covers happy-path inserts, autocommit refusal, timezone round-trip, capability-gated idempotency, SQLState classification, and batch atomicity. The bundled PostgreSQL dialect passes 100% of the contract via `PostgresDialectContractIT`. See the [Writing a new dialect](#writing-a-new-dialect) section above.

### `outbox-schema`

A JAR that contains only `.sql` files under `src/main/resources/sql/postgres/` — `outbox-publisher.sql` (mandatory) and `outbox-relay-extension.sql` (optional, polling relay only). Nothing on the classpath; intended to be unpacked by your migration tool or copied into your repo.

### `outbox-bom`

Standard Maven BOM. Manages only the eight `outbox-*` modules; **third-party runtime dependencies (Micrometer, SLF4J, OpenTelemetry, the PostgreSQL driver) are deliberately not pinned here** so the consumer's own BOM stack (Spring Boot, custom platform BOM) remains the single source of truth. Use it; do not pin module versions individually.

## Multi-module / hexagonal projects

Recommended placement when the consumer follows a hexagonal/clean architecture:

```
my-service/
├── domain                no outbox dependency           ← zero coupling
├── application           depends on outbox-core only    ← may reference Outbox in ports
└── infrastructure        depends on outbox-spring + outbox-jdbc (runtime)
```

In `application/pom.xml`:

```xml
<dependency>
    <groupId>io.github.lobofoltran</groupId>
    <artifactId>outbox-core</artifactId>
</dependency>
```

In `infrastructure/pom.xml`:

```xml
<dependency>
    <groupId>io.github.lobofoltran</groupId>
    <artifactId>outbox-spring</artifactId>
</dependency>
<dependency>
    <groupId>io.github.lobofoltran</groupId>
    <artifactId>outbox-jdbc</artifactId>
    <scope>runtime</scope>
</dependency>
```

Verify what actually leaks into your service:

```bash
./mvnw dependency:tree -Dincludes=io.github.lobofoltran
```

You should see exactly three artifacts: `outbox-core`, `outbox-spring`, `outbox-jdbc`.

## Configuration reference

Spring Boot properties (all optional unless noted). Property keys are also published in `META-INF/spring-configuration-metadata.json` so IDE autocomplete works out of the box.

| Property | Default | Description |
| --- | --- | --- |
| `io.github.lobofoltran.outbox.enabled` | `true` | Master switch. When `false`, `OutboxAutoConfiguration` publishes no beans. |
| `io.github.lobofoltran.outbox.table-name` | `outbox` | Override only if your migration uses another name. Validated against `[A-Za-z_][A-Za-z0-9_]*` at startup. |
| `io.github.lobofoltran.outbox.schema` | _(empty)_ | DB schema qualifier, e.g. `events`. When empty, the table is referenced unqualified. |
| `io.github.lobofoltran.outbox.metrics.enabled` | `true` | When `false`, skip the `MeteredOutbox` wrapper even if Micrometer is on the classpath. |
| `io.github.lobofoltran.outbox.tracing.enabled` | `true` | When `false`, skip the `TracedOutbox` wrapper even if OpenTelemetry is on the classpath. |
| `io.github.lobofoltran.outbox.health.enabled` | `true` | When `false`, do not register `OutboxHealthIndicator` even if Spring Boot Actuator is on the classpath. |

There are deliberately no properties for `IdGenerator`, `Clock`, or `OutboxDialect` — those are wired in code via [`JdbcOutboxBuilderCustomizer`](#jdbcoutboxbuildercustomizer--extending-the-autoconfig-without-replacing-the-bean) (Spring) or directly via `JdbcOutbox.Builder` (plain JDBC). Properties are reserved for boolean toggles and simple identifiers; everything that involves a behavior contract stays in Java to keep typing and IDE support strong.

## Metrics

When [Micrometer](https://micrometer.io/) is on the classpath and a `MeterRegistry` bean exists in the Spring context, `outbox-spring` automatically wraps the `Outbox` bean with `MeteredOutbox`. No code changes required.

### Add the module

```xml
<dependency>
    <groupId>io.github.lobofoltran</groupId>
    <artifactId>outbox-micrometer</artifactId>
    <scope>runtime</scope>
</dependency>
```

(In a Spring Boot 4 application with Actuator, you already have Micrometer. In other setups, add it explicitly.)

### What gets emitted

| Meter | Type | Tags | What it measures |
| --- | --- | --- | --- |
| `outbox.publish` | `Timer` | `aggregate_type`, `event_type`, `result` | Latency of `publish(...)` |
| `outbox.publish.bytes` | `DistributionSummary` | `aggregate_type`, `event_type` | Size of `payload` in bytes |

`result` is `success` or `failure`.

### Cardinality policy

The following tags are **deliberately not** emitted, to keep metrics cardinality bounded:

- `aggregate_id` — unbounded by design.
- `destination` — can be tenant-scoped (`orders.tenant-XYZ`) and explode.

If you need any of these in your observability stack, add them through a `MeterFilter` in your `MeterRegistry` and accept the cardinality cost — the library will not do that for you.

### Opt-out

```yaml
io.github.lobofoltran.outbox.metrics.enabled: false
```

The `Outbox` bean is published unwrapped (no `MeteredOutbox`).

### Plain JDBC (no Spring)

Wrap manually:

```java
Outbox raw = JdbcOutbox.builder()
    .connectionSupplier(() -> currentTxConnection(ds))
    .build();

Outbox outbox = new MeteredOutbox(raw, meterRegistry);
```

## Tracing

When [OpenTelemetry](https://opentelemetry.io/) is on the classpath and an `OpenTelemetry` bean exists in the Spring context, `outbox-spring` automatically wraps the `Outbox` bean with `TracedOutbox`. No code changes required.

### Add the module

```xml
<dependency>
    <groupId>io.github.lobofoltran</groupId>
    <artifactId>outbox-otel</artifactId>
    <scope>runtime</scope>
</dependency>
```

The OpenTelemetry API is declared `<scope>provided</scope>` — bring your own version (typically the one bundled with your OpenTelemetry Java agent or your application's `opentelemetry-bom` import). The library is binary-compatible with any OpenTelemetry 1.x API.

### What gets emitted

| Span | Span kind | Triggered by | Attributes |
| --- | --- | --- | --- |
| `outbox publish` | `PRODUCER` | `publish(OutboxEvent)` | `messaging.system=outbox`, `messaging.operation=publish`, `messaging.message.id`, `messaging.destination.name` (when non-null), `outbox.aggregate_type`, `outbox.event_type` |
| `outbox publish_batch` | `PRODUCER` | `publishAll(Iterable<OutboxEvent>)` | `messaging.system=outbox`, `messaging.operation=publish_batch`, `messaging.batch.message_count` |

`messaging.*` keys follow the OpenTelemetry semantic conventions for messaging systems. On exception, the span status is set to `ERROR` and the exception is recorded.

### Cardinality policy

Span names are deliberately low-cardinality — neither `aggregate_id` nor `destination` ever appears in the span name. The same rules that govern metric tags apply here.

### Opt-out

```yaml
io.github.lobofoltran.outbox.tracing.enabled: false
```

The `Outbox` bean is published unwrapped (no `TracedOutbox`).

### Plain JDBC (no Spring)

Wrap manually:

```java
Outbox raw = JdbcOutbox.builder()
    .connectionSupplier(() -> currentTxConnection(ds))
    .build();

Tracer tracer = openTelemetry.getTracer("io.github.lobofoltran.outbox");
Outbox outbox = new TracedOutbox(raw, tracer);
```

## Operating model: how events get delivered

This library only writes the event. To get events to a broker you have two supported paths — choose **one**:

### Option A — Polling relay

Run a separate program (or scheduled bean) that does:

```sql
SELECT ... FROM outbox
 WHERE status = 'PENDING' AND (next_attempt_at IS NULL OR next_attempt_at <= now())
 ORDER BY occurred_at
 LIMIT :batch
 FOR UPDATE SKIP LOCKED;
```

then publishes to the broker and updates the row to `SENT` / `FAILED`.

See the companion project [`outbox-relay`](#related-projects) for a ready-made relay with Kafka and RabbitMQ providers.

### Option B — CDC (Debezium)

Point Debezium at the `outbox` table. Use the Debezium Outbox Event Router SMT to map columns to Kafka topics. The publisher writes rows; Debezium does the rest. No relay process needed.

The library is intentionally agnostic between the two.

## Compatibility matrix

| `outbox-publisher` | Java | Spring Boot | Micrometer | OpenTelemetry | PostgreSQL | `schema_version` |
| --- | :---: | :---: | :---: | :---: | :---: | :---: |
| 0.1.x | 25 | 4.x | 1.16+ | 1.x | 14+ | 1 |

Notes:

- Micrometer and OpenTelemetry are `<scope>provided</scope><optional>true</optional>` in `outbox-spring` and the respective decorator modules — the consumer's BOM (Spring Boot, custom platform BOM) is the single source of truth for the chosen version.
- JDBC drivers must support the JSON column type (`org.postgresql:postgresql` 42.x is sufficient).
- Java 25 is the current build floor. The runtime floor will be revisited at 1.0.0.

## Build, test, release

```bash
./mvnw clean verify                       # build, tests, JaCoCo coverage gates, Checkstyle, Spotless
./mvnw -pl outbox-jdbc test               # run only the JDBC integration tests (requires Docker)
./mvnw -pl outbox-bom -am -Pbom-smoke-test verify   # exercises the BOM via maven-invoker (also gated in CI)
./mvnw -Ppit test -Dmutation.threshold=85 # nightly PIT mutation testing with an 85% aggregate floor
```

Per-module JaCoCo thresholds and PIT floors are documented in [`AGENTS.md`](./AGENTS.md). CI runs:

- **`build`** — `mvn -Pquality verify` on every PR and `main` push.
- **`bom-smoke`** — installs the reactor then resolves the BOM from a consumer project (`outbox-bom/src/it/consumer`).
- **`codeql`** — GitHub CodeQL Java analysis weekly + on every PR.
- **`pit`** — mutation testing nightly with the 85% aggregate floor.
- **`release`** — on `v*.*.*` tag push: signs JARs with the CI GPG key, generates a CycloneDX SBOM via `cyclonedx-maven-plugin`, deploys to GitHub Packages, and attaches signed artifacts + SBOM + public key to the GitHub Release. See [`SECURITY.md`](./SECURITY.md) for the signing key fingerprint and verification recipe.

## FAQ

**Why no Spring Data JPA?**
Because the outbox INSERT is on the hot path of every business transaction, and because the library must not force a persistence framework on the user. JPA brings entity caches, flush timing, and dialect quirks that hurt both performance and correctness here. ShedLock made the same call for the same reasons.

**Can I use this without Spring?**
Yes. Import `outbox-core` + `outbox-jdbc` and pass your own connection supplier.

**Does this guarantee ordering?**
The publisher only guarantees that events are persisted in the order their transactions commit. Delivery order is a relay/broker concern.

**Does this deduplicate events?**
By default, every dialect implements idempotency: writing twice with the same `OutboxEvent.id` is silently absorbed. Generate a stable id (e.g. UUIDv5 of the business key) when you want at-least-once safety; the second write becomes a no-op without surfacing an `OutboxIntegrityException`.

**Why is `Outbox` itself not discovered via `ServiceLoader`?**
`Outbox` discovery is explicit on purpose: Spring Boot consumers get the bean from `outbox-spring`'s autoconfig, plain-JDBC consumers call `JdbcOutbox.builder()...build()`. `ServiceLoader` is used internally by `outbox-jdbc` to discover `OutboxDialect` implementations (so you can plug in non-PostgreSQL databases without touching `outbox-jdbc`'s code).

**Can I publish across two databases?**
No — that defeats the purpose. The whole point is that the event and the business change live in the same transaction in the same database.

**What if my application uses Hibernate/JPA elsewhere?**
That is fine. `outbox-jdbc` participates in whichever transaction is currently active, including a JTA or Spring-managed JPA transaction. The library itself does not load Hibernate.

**Why JPMS if Spring Boot is on the classpath?**
The pure modules (`outbox-core`, `outbox-jdbc`) are JPMS-modular regardless of how the host application is packaged. Spring Boot apps run them as automatic modules on the classpath; modular hosts can put them on the module path. Both work.

## Related projects

- **`outbox-relay`** — a relay process that drains the `outbox` table using `FOR UPDATE SKIP LOCKED` and forwards events to Kafka, RabbitMQ, or other brokers via a pluggable SPI.
- **[Debezium Outbox Event Router](https://debezium.io/documentation/reference/stable/transformations/outbox-event-router.html)** — log-based CDC alternative to a polling relay.
- **[ShedLock](https://github.com/lukas-krecan/ShedLock)** — distributed lock for `@Scheduled` tasks; useful if you decide to colocate a relay inside your app.

## License

Released under the [MIT License](./LICENSE). By contributing, you agree that your contributions are licensed under the same terms.
