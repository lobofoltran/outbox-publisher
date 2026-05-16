# Roadmap — `outbox-publisher` v1.0.0

Execution plan from the current state (`README.md` + `AGENTS.md` only) to the first release published on GitHub Packages. Phases map one-to-one to PRs in line with the "one logical change per PR" rule.

This document is the source of truth for sequencing. Anything not listed here is out of scope for v1 and tracked in [Out of scope for v1](#out-of-scope-for-v1).

## Milestones

| Milestone | What it proves | PRs |
| --- | --- | --- |
| **M0** — Governance ready | Repo carries ADRs, PR template, CODEOWNERS, license, CONTRIBUTING. Ready to accept code. | F0 |
| **M1** — Build skeleton green | Parent + 6 empty modules compile. Spotless/Checkstyle/JaCoCo configured. Coverage gate passes trivially on empty code. | F1, F2 |
| **M2** — Core API frozen | `outbox-core` complete: interface, record, JPMS configured, 95/95 coverage. Other modules may depend on it. | F3 |
| **M3** — End-to-end happy path | A Spring Boot app can call `outbox.publish(event)` and the row appears in PostgreSQL. Covered by real IT. | F4, F5, F6 |
| **M3.5** — Observability ready | When Micrometer is on the classpath, `outbox.publish` latency and counts are exported automatically. | F6.5 |
| **M4** — Release 1.0.0 | Tag `v1.0.0` creates the release, signed artifacts published, README points at a real version. | F7, F8 |

## Phases / PRs

### F0 — Governance bootstrap

**Goal:** clear every governance gap before any code lands.

**Deliverables:**

- `docs/adr/0001-foundations.md` — backfill of every locked decision in `AGENTS.md`: Maven, Java 25, PostgreSQL-only v1, no Spring Data JPA, JPMS, no bundled migrations, SLF4J, JaCoCo 90/90 floor, PIT nightly, release-please, GitHub Packages, MIT license.
- `docs/adr/template.md` — MADR template for future ADRs.
- `.github/pull_request_template.md` — fields: roadmap stage, risk, rollback, observability impact, ADR/PRD link.
- `.github/CODEOWNERS` — `@lobofoltran` for now.
- `LICENSE` — MIT.
- `CONTRIBUTING.md` — short stub pointing to `AGENTS.md`.
- `CHANGELOG.md` — placeholder with the header `release-please` expects.
- `.gitignore` (Maven + IDEs + macOS) and `.editorconfig`.

**Acceptance:**

- All files committed; no code yet.
- `git status` clean.
- `README.md` and `AGENTS.md` remain the only design references — F0 only fills in the gaps they pointed to.

**Blockers:** none.

### F1 — Maven multi-module skeleton

**Goal:** the five modules compile empty with quality tooling active.

**Deliverables:**

- Parent `pom.xml`:
  - `<packaging>pom</packaging>`
  - `<revision>1.0.0-SNAPSHOT</revision>` + `flatten-maven-plugin`
  - `<modules>` listing the five
  - Centralized `<dependencyManagement>`
  - Plugins: `maven-compiler-plugin` (release 25), `maven-surefire-plugin`, `maven-failsafe-plugin`, `jacoco-maven-plugin`, `spotless-maven-plugin`, `maven-checkstyle-plugin`, `error_prone_core`
  - Profile `quality` activating JaCoCo gates (90/90 bundle).
  - Profile `release` activating `maven-gpg-plugin`, `maven-source-plugin`, `maven-javadoc-plugin`.
- Six module directories, each with a minimal `pom.xml` (no code yet):
  - `outbox-core/pom.xml`
  - `outbox-jdbc/pom.xml`
  - `outbox-spring/pom.xml`
  - `outbox-micrometer/pom.xml`
  - `outbox-schema/pom.xml` (jar packaging, resources only)
  - `outbox-bom/pom.xml` (pom packaging, `<dependencyManagement>` listing the others)
- `.mvn/wrapper/`, `mvnw`, `mvnw.cmd`.
- `config/checkstyle.xml` — Google Checks base + explicit prohibitions from `AGENTS.md` (no wildcards, no `var` in main, no JUnit 4 imports, etc.).
- `config/spotless.xml` — Google Java Format + import order from §3.11.

**Acceptance:**

- `./mvnw -B -ntp -Pquality verify` is green.
- `./mvnw clean install` installs the BOM and the five empty JARs into `~/.m2`.
- JaCoCo emits an aggregated report (empty but present).

**Risks:**

- **Spotless + Java 25:** confirm Google Java Format version supporting Java 25. Plan B: Palantir Java Format.
- **ErrorProne + Java 25:** version `2.27+` required. May need `-XDcompilePolicy=simple --should-stop=ifError=FLOW`.
- **`jacoco-maven-plugin` + Java 25:** version `0.8.12+`.

### F2 — CI workflows + branch protection

**Goal:** PRs require a green build.

**Deliverables:**

- `.github/workflows/build.yml` — `setup-java@v4` (Temurin 25), `cache: 'maven'`, `./mvnw -B -ntp -Pquality verify`.
- `.github/workflows/release-please.yml` — `googleapis/release-please-action@v4` configured for Maven (`release-type: maven`).
- `.github/workflows/release.yml` — triggers on `v*` tags. Imports GPG key, runs `./mvnw -B -ntp -Prelease deploy`, attaches JARs to the GitHub Release.
- `.github/workflows/pit.yml` — daily cron 06:00 UTC + `workflow_dispatch`. Runs `./mvnw -B -ntp -Ppit test`.
- `.github/dependabot.yml` — weekly Maven + GitHub Actions.
- `release-please-config.json` + `.release-please-manifest.json`.

**Acceptance:**

- A trivial test PR (README typo) triggers `build.yml` and goes green.
- `release-please.yml` opens its first release PR draft.
- Branch protection rules from `AGENTS.md > CI/CD > Branch protection` applied via the GitHub UI.

**Risks:**

- **`release-type: maven` + `flatten-maven-plugin`:** release-please must rewrite `<revision>` in the parent. Validate it recognizes `${revision}` before merging F2.

### F3 — `outbox-core`

**Goal:** stable public API with 95/95 coverage.

**Deliverables (`outbox-core/`):**

- `src/main/java/module-info.java`:

  ```java
  module io.github.lobofoltran.outbox.core {
      requires org.slf4j;
      exports io.github.lobofoltran.outbox;
      uses io.github.lobofoltran.outbox.Outbox;
  }
  ```

- `src/main/java/io/github/lobofoltran/outbox/Outbox.java` — single-method interface.
- `OutboxEvent.java` — record with builder.
- `OutboxException.java`.
- Unit tests covering: builder defaults, required-field validation, null rejection, record equality, exception construction.

**Acceptance:**

- `./mvnw -pl outbox-core verify` green.
- JaCoCo reports ≥95% line and branch.
- PIT (run locally) ≥85%.
- No `@SuppressWarnings`.
- No `var` in `src/main/`.

**Open decisions resolved here:**

- **Headers serialization:** decision recorded as ADR-0002 ("hand-rolled JSON, `Map<String,String>`") because it surfaces in the public API (`OutboxEvent.headers()` type).

### F4 — `outbox-schema`

**Goal:** the SQL file exists and is reachable as a classpath resource.

**Deliverables (`outbox-schema/`):**

- `src/main/resources/sql/postgres/outbox.sql` — DDL already designed (table + two partial indexes).
- `src/test/java/.../SchemaResourceTest.java` — verifies the file loads via classpath (sentinel against accidental rename).

**Acceptance:**

- `./mvnw -pl outbox-schema verify` green.
- The packaged JAR contains `sql/postgres/outbox.sql`.

**Risk:** none. This phase exists mainly so the BOM closes with five artifacts.

### F5 — `outbox-jdbc`

**Goal:** `INSERT` works against real PostgreSQL with 100/100 coverage.

**Deliverables (`outbox-jdbc/`):**

- `module-info.java`:

  ```java
  module io.github.lobofoltran.outbox.jdbc {
      requires io.github.lobofoltran.outbox.core;
      requires java.sql;
      requires org.slf4j;
      provides io.github.lobofoltran.outbox.Outbox
          with io.github.lobofoltran.outbox.jdbc.JdbcOutbox;
  }
  ```

- `JdbcOutbox` (package `...outbox.jdbc`, **not exported**):
  - Builder with `connectionSupplier`, `tableName`, `schema`, `clock`, `idGenerator`.
  - `publish(OutboxEvent)` → `INSERT INTO ... VALUES (?, ?, ?, ...)` via `PreparedStatement`, connection obtained from the supplier.
- `UuidV7Generator` (package-private) — RFC 9562 implementation.
- Internal `headers` serializer (`Map<String,String>` → JSON, no external dependency).
- `META-INF/services/io.github.lobofoltran.outbox.Outbox` (for classpath consumers).
- Integration tests with Testcontainers PostgreSQL 16:
  - `JdbcOutboxIT` — `publish` results in a persisted row; every column matches.
  - `JdbcOutboxTxIT` — `publish` inside a rolled-back transaction does **not** persist; inside a committed one it does.
  - `JdbcOutboxHeadersIT` — special characters, empty, single, multiple pairs.
  - `JdbcOutboxConcurrencyIT` — N threads, no deadlock, unique IDs.

**Acceptance:**

- `./mvnw -pl outbox-jdbc verify` green; Testcontainers operational.
- JaCoCo 100/100 (line and branch).
- PIT ≥90%.
- Zero JDBC mocking; everything against real PostgreSQL.

**Risks:**

- **JPMS + JDBC driver:** `org.postgresql:postgresql` is an automatic module. Validate that `requires java.sql` is enough and that the driver is discovered via `ServiceLoader`.
- **Testcontainers + Docker in CI:** GitHub-hosted Linux runners ship Docker; validate in F2.

### F6 — `outbox-spring`

**Goal:** Spring Boot 3 autoconfiguration wires everything.

**Deliverables (`outbox-spring/`):**

- **No `module-info.java`** (automatic module).
- `OutboxAutoConfiguration`:
  - `@ConditionalOnClass(Outbox.class)`
  - `@ConditionalOnProperty("io.github.lobofoltran.outbox.enabled", matchIfMissing = true)`
  - `@AutoConfigureAfter(DataSourceAutoConfiguration.class)`
  - Creates an `Outbox` bean wrapping `JdbcOutbox` with `connectionSupplier = () -> DataSourceUtils.getConnection(dataSource)`.
- `OutboxProperties` — `@ConfigurationProperties("io.github.lobofoltran.outbox")`.
- `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
- Tests:
  - `OutboxAutoConfigurationTest` — `ApplicationContextRunner` validates the bean, conditions, properties.
  - `OutboxSpringBootIT` — `@SpringBootTest` + Testcontainers PG; verifies the INSERT participates in the Spring-managed transaction.

**Acceptance:**

- JaCoCo ≥90/90.
- IT proves a Spring rollback removes the outbox row.

### F6.5 — `outbox-micrometer`

**Goal:** when Micrometer is on the classpath, every `publish` emits a Timer and a Counter — no code changes in the consumer.

**Deliverables (`outbox-micrometer/`):**

- `module-info.java`:

  ```java
  module io.github.lobofoltran.outbox.micrometer {
      requires io.github.lobofoltran.outbox.core;
      requires micrometer.core;
      exports io.github.lobofoltran.outbox.micrometer;
  }
  ```

- `MeteredOutbox` — decorator that wraps any `Outbox` with a `MeterRegistry`:
  - Times every `publish` call.
  - Tags: `aggregate_type`, `event_type`, `result` (`success` | `failure`).
  - Records a `DistributionSummary` for `payload` size in bytes.
  - **Deliberately does NOT tag** `aggregate_id` or `destination` — those would explode cardinality. Documented inline and in ADR-0004.
- Unit tests against a `SimpleMeterRegistry`:
  - Success path increments counter with `result=success`.
  - Failure path increments counter with `result=failure` and rethrows.
  - Tags carry the values from `OutboxEvent`.
  - `payload.bytes` summary records the byte length.

**Changes elsewhere:**

- `outbox-spring` gains a soft dependency on `micrometer-core` (`<optional>true</optional>`).
- `OutboxAutoConfiguration` adds a nested `@Configuration` activated by:
  - `@ConditionalOnClass(MeterRegistry.class)`
  - `@ConditionalOnBean(MeterRegistry.class)`
  - `@ConditionalOnProperty(prefix = "io.github.lobofoltran.outbox.metrics", name = "enabled", matchIfMissing = true)`
  When all three hold, the `Outbox` bean published to the context is a `MeteredOutbox` wrapping `JdbcOutbox`. Otherwise, `JdbcOutbox` is exposed directly.
- ADR-0004 `metrics-and-cardinality.md` documents:
  - Why Micrometer (not a custom SPI).
  - Why the chosen tags and the rejected ones.
  - The opt-out switch.
- `outbox-bom` adds `outbox-micrometer` to `<dependencyManagement>` (folded into F7).
- README gains a short "Metrics" section.

**Acceptance:**

- `./mvnw -pl outbox-micrometer verify` green.
- JaCoCo ≥95% line and branch on the module.
- PIT ≥90% on the module.
- IT in `outbox-spring` proves: with `MeterRegistry` in the context, calling `outbox.publish` increments `outbox.publish` counter with `result=success`.

**Risks:**

- **Cardinality regression** if a future change adds a high-cardinality tag. Mitigation: ADR-0004 lists every allowed tag explicitly; new tags require an ADR amendment.
- **JPMS + Micrometer:** `micrometer-core` is an automatic module; module name is `micrometer.core`. Validate before merging.

### F7 — `outbox-bom`

**Goal:** publishable BOM.

**Deliverables:**

- `outbox-bom/pom.xml` — `<dependencyManagement>` listing the other four modules with `${project.version}`.

**Acceptance:**

- `./mvnw -pl outbox-bom verify` green.
- A scratch consumer project imports the BOM and omits per-module versions.

**Risk:** none — XML only.

### F8 — Release 1.0.0

**Goal:** first public release.

**Sequence:**

1. Merge the open PR from `release-please-bot`: bumps `<revision>` to `1.0.0`, updates `CHANGELOG.md`, creates tag `v1.0.0`, opens the GitHub Release.
2. `release.yml` fires on the `v1.0.0` push → imports GPG → `./mvnw -Prelease deploy` → artifacts published to `https://maven.pkg.github.com/lobofoltran/outbox-publisher`.
3. Signed JARs (`*.jar`, `*.jar.asc`) attached to the GitHub Release.
4. README updated: replace placeholder `${outbox.version}` with `1.0.0`; remove the "Status: early design" banner.

**Acceptance:**

- An external smoketest project pulls `1.0.0` from GitHub Packages and successfully runs `outbox.publish(...)` against a local PostgreSQL.
- `gpg --verify` succeeds on at least one published JAR.
- GitHub Release shows an accurate changelog.

## Effort and dependencies

| Phase | Relative effort | Depends on |
| --- | --- | --- |
| F0 | S | — |
| F1 | M | F0 |
| F2 | M | F1 |
| F3 | M | F1 |
| F4 | XS | F1 |
| F5 | L | F3, F4 |
| F6 | M | F3, F5 |
| F6.5 | M | F3, F6 |
| F7 | XS | F3, F5, F6, F6.5 |
| F8 | S | F7 |

Calendar dates are not committed. F3 and F4 may run in parallel. F6 can start as soon as F5 stabilizes `JdbcOutbox`'s public surface. F6.5 only touches `outbox-micrometer` and a small bit of `outbox-spring`, so it can land independently after F6.

## Out of scope for v1

Tracked either in `AGENTS.md > Open decisions` or as future ADRs:

- INSERT retry policy embedded in the API (delegated to the caller via `@Retryable` / Resilience4j).
- MySQL / Oracle support (PostgreSQL-only).
- The outbox-relay project (separate repository, out of this plan).
- Maven Central publication (planned for v2 if the library goes beyond the org).
- Reactive API (`Mono<Void> publish(...)`).
- Idempotency of `publish` by key (non-goal; see README FAQ).

## Cross-cutting risks

| Risk | Mitigation | Phase most affected |
| --- | --- | --- |
| Java 25 too new, plugin incompatibilities | Pin minimum compatible versions (JaCoCo 0.8.12+, ErrorProne 2.27+, Surefire 3.2.5+). Plan B: Java 21. | F1 |
| Spring Boot 3 + JPMS strict modules | `outbox-spring` stays automatic; surgically `opens` `OutboxEvent` if Jackson uses reflection. | F6 |
| GitHub Packages auth hurts adoption | Documented in README; ADR-0003 (future) decides Maven Central migration. | F8+ |
| PIT slow / flaky against mutated PG-IT | PIT runs nightly only; never blocks PRs. | F5 |
| Spotless reformatting hand-tuned code | `// @formatter:off` only with inline justification. | F1+ |
