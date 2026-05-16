# outbox-publisher

A Java library that gives your application a single API — `outbox.publish(event)` — to write events to an `outbox` table inside the caller's database transaction (Transactional Outbox pattern).

The application **does not know** how those events will be delivered to the outside world. That responsibility belongs to a relay (polling-based, see [`outbox-relay`](#related-projects)) or to a CDC pipeline like Debezium. The boundary between this library and any consumer is the **`outbox` table** — there is no Java coupling between this project and the relay.

> **Status:** early design. No code published yet. This README describes the target shape of the library. See [`ROADMAP.md`](./ROADMAP.md) for the execution plan.

## Table of contents

- [Why this library](#why-this-library)
- [What it is not](#what-it-is-not)
- [Architecture](#architecture)
- [Installation](#installation)
- [Quick start (Spring Boot)](#quick-start-spring-boot)
- [Quick start (plain JDBC)](#quick-start-plain-jdbc)
- [The `outbox` table contract](#the-outbox-table-contract)
- [Module-by-module reference](#module-by-module-reference)
- [Multi-module / hexagonal projects](#multi-module--hexagonal-projects)
- [Configuration reference](#configuration-reference)
- [Metrics](#metrics)
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
                          │ provides Outbox via ServiceLoader
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

The library ships six Maven modules:

| Module | Purpose | Application sees it? |
| --- | --- | --- |
| `outbox-core` | `Outbox` interface, `OutboxEvent` record, exceptions | Yes (compile) |
| `outbox-jdbc` | JDBC implementation of `Outbox` | No (runtime) |
| `outbox-spring` | Spring Boot autoconfiguration | No (runtime) |
| `outbox-micrometer` | `MeteredOutbox` decorator — Micrometer Timer/Counter on every publish | No (runtime, optional) |
| `outbox-schema` | Example SQL DDL as classpath resources | No (optional) |
| `outbox-bom` | Bill of Materials for version management | imported in BOM |

## Installation

### 1. Configure the GitHub Packages repository

Artifacts are published to GitHub Packages, which requires authentication even for public packages. Add the following to your `~/.m2/settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>github-lobofoltran</id>
      <username>YOUR_GITHUB_USERNAME</username>
      <password>YOUR_GITHUB_PAT_WITH_read:packages</password>
    </server>
  </servers>
</settings>
```

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

The `outbox-schema` module ships a single SQL file as a classpath resource. Copy it into your migration tool of choice (Flyway, Liquibase, hand-rolled — your call):

```
outbox-schema/src/main/resources/sql/postgres/
└── outbox.sql
```

It contains the table plus two partial indexes — one to back the relay's polling query, one to back the retention purge. See [The `outbox` table contract](#the-outbox-table-contract) for the exact DDL.

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

`Outbox` itself is discovered via `ServiceLoader` from `outbox-jdbc`, so you can also obtain it without referencing the implementation class:

```java
Outbox outbox = ServiceLoader.load(Outbox.class).findFirst().orElseThrow();
```

## The `outbox` table contract

This is the **only** integration surface between `outbox-publisher` and any consumer. The full DDL (PostgreSQL) is a single file shipped by `outbox-schema`:

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
    occurred_at     TIMESTAMP    NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP,
    published_at    TIMESTAMP,
    last_error      TEXT,
    schema_version  SMALLINT     NOT NULL DEFAULT 1
);

CREATE INDEX idx_outbox_pending
    ON outbox (next_attempt_at, occurred_at)
    WHERE status = 'PENDING';

CREATE INDEX idx_outbox_sent
    ON outbox (published_at)
    WHERE status = 'SENT';
```

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
}

public class OutboxException extends RuntimeException { ... }
```

JPMS:

```java
module io.github.lobofoltran.outbox.core {
    requires org.slf4j;
    exports io.github.lobofoltran.outbox;
    uses io.github.lobofoltran.outbox.Outbox;
}
```

### `outbox-jdbc`

JDBC implementation. Internals are **not** exported.

```java
module io.github.lobofoltran.outbox.jdbc {
    requires io.github.lobofoltran.outbox.core;
    requires java.sql;
    requires org.slf4j;
    provides io.github.lobofoltran.outbox.Outbox
        with io.github.lobofoltran.outbox.jdbc.JdbcOutbox;
}
```

Consumers cannot `import io.github.lobofoltran.outbox.jdbc.JdbcOutbox`. They obtain `Outbox` either through Spring autoconfiguration or `ServiceLoader`.

### `outbox-spring`

Spring Boot 3.x autoconfiguration. Automatic Maven module (no `module-info.java`). It:

- Creates the `Outbox` bean wired to the application's `DataSource`.
- Hooks `JdbcOutbox` into Spring's transaction manager so the INSERT joins the current transaction.
- Reads `io.github.lobofoltran.outbox.*` properties (see [Configuration reference](#configuration-reference)).

### `outbox-schema`

A JAR that contains only `.sql` files under `src/main/resources/sql/postgres/`. Nothing on the classpath; intended to be unpacked by your migration tool or copied into your repo.

### `outbox-bom`

Standard Maven BOM. Use it; do not pin module versions individually.

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

Spring Boot properties (all optional unless noted):

| Property | Default | Description |
| --- | --- | --- |
| `io.github.lobofoltran.outbox.enabled` | `true` | Master switch. |
| `io.github.lobofoltran.outbox.table-name` | `outbox` | Override only if your migration uses another name. |
| `io.github.lobofoltran.outbox.schema` | _(empty)_ | DB schema, e.g. `public`. |
| `io.github.lobofoltran.outbox.id-generator` | `UUID_V7` | `UUID_V7` or `UUID_V4`. |
| `io.github.lobofoltran.outbox.clock` | _(system)_ | Override for tests. |
| `io.github.lobofoltran.outbox.metrics.enabled` | `true` | When `false`, skip the `MeteredOutbox` wrapper even if Micrometer is present. |

Plain JDBC: same options exposed via `JdbcOutbox.Builder`.

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

(In a Spring Boot 3 application with Actuator, you already have Micrometer. In other setups, add it explicitly.)

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

| `outbox-publisher` | Java | Spring Boot | PostgreSQL | `schema_version` |
| --- | :---: | :---: | :---: | :---: |
| 1.x | 25 | 3.x | 14+ | 1 |

JDBC drivers must support the JSON column type (`org.postgresql:postgresql` 42.x is sufficient).

## Build, test, release

```bash
./mvnw clean verify          # build, tests, coverage gates
./mvnw -pl outbox-jdbc test  # run only the JDBC integration tests (requires Docker)
```

Test coverage gates are enforced by JaCoCo and PIT. See [`AGENTS.md`](./AGENTS.md) for the per-module thresholds.

## FAQ

**Why no Spring Data JPA?**
Because the outbox INSERT is on the hot path of every business transaction, and because the library must not force a persistence framework on the user. JPA brings entity caches, flush timing, and dialect quirks that hurt both performance and correctness here. ShedLock made the same call for the same reasons.

**Can I use this without Spring?**
Yes. Import `outbox-core` + `outbox-jdbc` and pass your own connection supplier.

**Does this guarantee ordering?**
The publisher only guarantees that events are persisted in the order their transactions commit. Delivery order is a relay/broker concern.

**Does this deduplicate events?**
No. Each call to `publish` creates a new row. If you need idempotency on the publish side, generate a stable `OutboxEvent.id` (e.g. UUIDv5 of the business key).

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

To be defined.
