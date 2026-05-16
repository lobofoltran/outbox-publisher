# Plain-JDBC example

End-to-end example showing `outbox-publisher` outside Spring:

- HikariCP-managed `DataSource`,
- explicit transaction boundary (`autoCommit=false`, `commit()` / `rollback()` by hand),
- `JdbcOutbox.builder().connectionSupplier(...).build()` — **no** `.dialect(...)`
  call, the `PostgresDialect` is auto-detected on first publish,
- a Testcontainers PostgreSQL integration test that asserts both the happy
  path and the rollback path: when the business write fails, the outbox row
  is gone too (because both writes shared one `Connection` and one transaction).

## Run

```bash
# from the repo root, install the reactor first
./mvnw -B -ntp -DskipTests install

# build the example
mvn -B -ntp -f examples/plain-jdbc/pom.xml verify
```

Override the BOM version when needed:

```bash
mvn -B -ntp -Doutbox.version=0.2.0-SNAPSHOT \
    -f examples/plain-jdbc/pom.xml verify
```

## What to look at

- `OrderShipmentService.java` — the integration code. Pay attention to
  `requireCurrentConnection()`, the `ThreadLocal<Connection>` that ties the
  `ConnectionSupplier` returned to `JdbcOutbox` to the same `Connection` the
  business INSERT runs on.
- `OrderShipmentServiceIT.java` — `shipAndFail(...)` proves the rollback
  semantics: even though `outbox.publish(...)` succeeded inside the
  `try` block, the surrounding `connection.rollback()` call wipes both rows.
