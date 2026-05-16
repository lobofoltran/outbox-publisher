# outbox-publisher examples

Two end-to-end consumer projects that mirror what an external adopter would
build against the published artifacts. They are intentionally **not** part of
the Maven reactor (`<modules>` in the parent POM) — they live here as
documentation, smoke tests, and CI fixtures.

Each example has its own self-contained `pom.xml` (no parent), imports
`outbox-bom` from the local Maven repository, and is invoked through an
explicit `-f` flag so the release reactor stays untouched.

## Prerequisite

Install the reactor once so the BOM and the modules are available locally:

```bash
./mvnw -B -ntp -DskipTests install
```

## Examples

### `spring-boot-quickstart`

Spring Boot 4 application that autoconfigures `Outbox` from a `DataSource`,
uses Flyway to apply `outbox-publisher.sql`, and exposes a single
`@Service` calling `outbox.publish(...)` inside a `@Transactional` method.
A `@SpringBootTest` against Testcontainers PostgreSQL asserts the row landed.
README also covers two Spring-Boot-4-specific gotchas: the
`spring-boot-starter-flyway` requirement and the GitHub Packages auth setup.

```bash
mvn -B -ntp -f examples/spring-boot-quickstart/pom.xml verify
```

### `plain-jdbc`

Pure JDBC + HikariCP, no Spring. Builds `JdbcOutbox` by hand, wires a
`ConnectionSupplier` backed by a `ThreadLocal<Connection>`, and demonstrates
both the happy commit path and a rollback path that proves the outbox row
disappears together with the business row.

```bash
mvn -B -ntp -f examples/plain-jdbc/pom.xml verify
```
