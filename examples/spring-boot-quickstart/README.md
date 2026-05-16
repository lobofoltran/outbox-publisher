# Spring Boot 4 quickstart

End-to-end example showing how a Spring Boot 4 application picks up
`outbox-publisher` through autoconfiguration:

- a single `@Service` calls `outbox.publish(...)` inside a `@Transactional` method,
- Flyway applies `outbox-publisher.sql` (and an `orders` table) at boot,
- a `@SpringBootTest` against a Testcontainers PostgreSQL asserts the row landed.

The application code only depends on `outbox-core`. The `outbox-spring`
dependency is on the runtime classpath and does the wiring.

## Run

```bash
# from the repo root, install the reactor so the BOM is resolvable from local m2
./mvnw -B -ntp -DskipTests install

# build the example
mvn -B -ntp -f examples/spring-boot-quickstart/pom.xml verify
```

If the parent reactor was built with a non-default `<revision>`, pass it through:

```bash
mvn -B -ntp -Doutbox.version=0.2.0-SNAPSHOT \
    -f examples/spring-boot-quickstart/pom.xml verify
```

## Spring Boot 4 + Flyway

Spring Boot 4 split the JDBC and Flyway autoconfiguration into per-domain
starter modules. **`flyway-core` alone is no longer enough** — the
`FlywayAutoConfiguration` class lives in `spring-boot-starter-flyway` and the
auto-detection is conditional on it being on the classpath. If you only pull in
`flyway-core`, the migration scripts under `db/migration/*.sql` will be
silently ignored at boot.

The fix is to declare the starter explicitly, as this example does:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-flyway</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-database-postgresql</artifactId>
</dependency>
```

(`flyway-database-postgresql` is required because Flyway 10+ moved each
database backend out of `flyway-core`.)

## GitHub Packages authentication

The published artifacts live on **GitHub Packages**. Even though the repo is
public, GitHub still requires a Personal Access Token with the
`read:packages` scope for Maven to download the JARs.

In your `~/.m2/settings.xml`, declare a server with a unique id and reference
that id from the repository in your `pom.xml`. The repository id used in this
example, `lobofoltran-outbox`, is intentionally unique — do **not** use a
generic id like `github` because Maven matches `<server>` to `<repository>`
strictly by id, and a generic name will collide with any other GitHub Packages
repo you consume.

**Do not paste the raw PAT into `settings.xml`.** Reference an environment
variable instead and export it from a vault-backed shell init (1Password CLI,
`pass`, `gh auth token`, …). See the [root README](../../README.md#1-configure-the-github-packages-repository)
for the full rationale and the encrypted-password alternative.

`~/.m2/settings.xml`:

```xml
<settings>
    <servers>
        <server>
            <id>lobofoltran-outbox</id>
            <username>${env.GITHUB_USERNAME}</username>
            <password>${env.GITHUB_TOKEN}</password>
        </server>
    </servers>
</settings>
```

```sh
export GITHUB_USERNAME="your-github-handle"
export GITHUB_TOKEN="$(gh auth token)"   # PAT with read:packages
chmod 600 ~/.m2/settings.xml
```

`pom.xml` (in the consuming project):

```xml
<repositories>
    <repository>
        <id>lobofoltran-outbox</id>
        <url>https://maven.pkg.github.com/lobofoltran/outbox-publisher</url>
        <releases>
            <enabled>true</enabled>
        </releases>
        <snapshots>
            <enabled>false</enabled>
        </snapshots>
    </repository>
</repositories>
```

The `<releases>` / `<snapshots>` block reflects the locked CI policy:
**only tagged releases are published**; there are no `-SNAPSHOT` artifacts on
GitHub Packages (see `AGENTS.md > CI/CD > Locked decisions`).

## Customizing the JdbcOutbox builder

To override defaults like the `IdGenerator` or `Clock` while keeping the
autoconfigured `Outbox` bean, you contribute a `JdbcOutboxBuilderCustomizer`:

```java
// TODO(agent-14): JdbcOutboxBuilderCustomizer ships in feat/spring-autoconfig-overhaul.
// Until that change merges, replace the autoconfigured Outbox with a hand-built one
// (see plain-jdbc example) or wait for the next release.
@Bean
JdbcOutboxBuilderCustomizer randomUuidIdGenerator() {
    return builder -> builder.idGenerator(clock -> java.util.UUID.randomUUID());
}
```

See `OutboxCustomization.java` for the placeholder bean.
