# AGENTS.md — outbox-publisher

Operational notes for agents and contributors working on this repository.

## Purpose

Multi-module library that exposes a **single API** to the application:

```java
outbox.publish(event);
```

It writes the event to the `outbox` table inside the caller's transaction. It knows nothing about brokers, relays, or CDC.

## Non-negotiable principles

1. **The application only sees `outbox-core`.** All other modules are runtime or internal.
2. **No Spring Data JPA.** Plain JDBC. Reasons: transactional control, performance, and not coupling to the user's persistence layer.
3. **No Java dependency on the `outbox-relay` repository.** The boundary is the table. If the temptation to share types via a shared JAR appears, refuse it.
4. **Strict JPMS in the pure modules** (`outbox-core`, `outbox-jdbc`). `outbox-spring` stays as an automatic module.
5. **DDL is versioned** (`schema_version` column in the table). Destructive changes require a compatibility window.

## Planned module structure

```
outbox-publisher/
├── outbox-core         interface Outbox, record OutboxEvent     (JPMS)
├── outbox-jdbc         JdbcOutbox implements Outbox             (JPMS, provides via ServiceLoader)
├── outbox-spring       Spring Boot autoconfig                   (automatic module)
├── outbox-micrometer   MeteredOutbox decorator                  (JPMS, optional)
├── outbox-schema       Example SQL (no Flyway/Liquibase bundled) (resources only)
└── outbox-bom          Version management
```

## Locked decisions

- **Build tool:** Maven
- **Java:** 25 (LTS)
- **Supported databases (v1):** PostgreSQL only
- **Migrations:** **do not** bundle Flyway/Liquibase. The `outbox-schema` module ships example SQL as resources only; each consuming microservice applies it however it sees fit.
- **Logging:** SLF4J (no implementation pinned)
- **Maven `groupId`:** `io.github.lobofoltran`
- **Root package:** `io.github.lobofoltran.outbox`
- **License:** MIT

## Table contract (reference)

The schema is split into two scripts under `outbox-schema/src/main/resources/sql/postgres/`. The publisher script is mandatory; the relay extension is applied **only** by adopters that run the polling relay. CDC adopters (Debezium etc.) skip the relay extension entirely. See ADR-0007 for the rationale.

All wall-clock timestamp columns are `TIMESTAMPTZ` (timestamp with time zone) so two JVMs in different zones writing the same `Instant` produce the same row. See ADR-0005.

### Publisher table (`outbox-publisher.sql`)

Mandatory. Contains only the columns the library writes via `outbox.publish(event)`.

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

### Relay extension (`outbox-relay-extension.sql`)

Optional. Adds the lifecycle columns and partial indexes consumed by the polling relay. Idempotent (`ADD COLUMN IF NOT EXISTS`, `CREATE INDEX IF NOT EXISTS`) so it can be applied later on a CDC-only deployment without data migration.

```sql
ALTER TABLE outbox
    ADD COLUMN IF NOT EXISTS status          VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN IF NOT EXISTS attempts        INT         NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS published_at    TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_error      TEXT;

-- Relay polling: SELECT ... WHERE status='PENDING' AND (next_attempt_at IS NULL OR next_attempt_at <= now())
--                ORDER BY occurred_at LIMIT N FOR UPDATE SKIP LOCKED
CREATE INDEX IF NOT EXISTS idx_outbox_pending
    ON outbox (next_attempt_at, occurred_at)
    WHERE status = 'PENDING';

-- Retention purge: DELETE FROM outbox WHERE status='SENT' AND published_at < :cutoff
CREATE INDEX IF NOT EXISTS idx_outbox_sent
    ON outbox (published_at)
    WHERE status = 'SENT';
```

Index design notes:

- Both indexes are **partial** — they index only rows in the relevant status, which is a tiny fraction of total rows under healthy operation.
- `idx_outbox_pending` does **not** include `status` in the key (it's already pinned by the partial `WHERE`). Key order is `(next_attempt_at, occurred_at)` so the planner can cheaply skip backed-off rows and then read in `ORDER BY occurred_at`.
- `idx_outbox_sent` exists solely for retention deletes; the relay does not read sent rows during normal operation.

## Conventions

- Root package: `io.github.lobofoltran.outbox`
- No `public` unless necessary — prefer package-private plus tightly controlled JPMS `exports`.

## Coding standards

Aligned with the org-wide Java coding standard (§3.6–§3.11). Rules are enforced via Spotless + Checkstyle + ErrorProne; violations fail the build.

### Collections and boxing (§3.6)

| Forbidden | Use instead |
| --- | --- |
| `new Integer(...)`, `new Long(...)`, `new Boolean(...)`, `new Double(...)`, `new Byte(...)`, `new Short(...)`, `new Float(...)`, `new Character(...)` (boxing constructors) | `Integer.valueOf(...)` etc. — or just autoboxing |
| `Arrays.asList(...)` in production code | `List.of(...)` (immutable, null-rejecting) |
| `Collections.singletonList(...)`, `Collections.singleton(...)`, `Collections.singletonMap(...)` | `List.of(x)`, `Set.of(x)`, `Map.of(k, v)` |
| `Collections.emptyList()` / `emptySet()` / `emptyMap()` (when an explicit empty literal is fine) | `List.of()`, `Set.of()`, `Map.of()` |

Tests may use the legacy helpers when fluency is gained, but the immutable factories are still preferred.

### Generics and type safety (§3.7)

- No raw types. Always parameterize collections, streams, generic interfaces.
- `@SuppressWarnings("rawtypes")` is forbidden — refactor to proper generics.
- `@SuppressWarnings("boxing")` is forbidden — use the right boxing path or the right primitive type.
- `@SuppressWarnings("unchecked")` is permitted only with an inline comment explaining the unsafe cast and why it cannot be avoided.

### Equality, hashCode, serialization (§3.8)

- Prefer **records** — equality, hash code, and `toString()` are derived. `OutboxEvent` is a record, by design.
- For non-record classes, use `java.util.Objects.equals(...)` and `java.util.Objects.hash(...)`. The legacy `int result = 1; result = 31 * result + ...` boilerplate is forbidden.
- Override `hashCode()` whenever you override `equals()`, and vice versa.
- `Serializable` implementations must declare `private static final long serialVersionUID`. Java serialization is not used in this library (wire payload is opaque `byte[]`), but the rule stands.

### Exceptions (§3.9)

- Do not declare checked exceptions that are never thrown.
- Catch blocks must use the exception (log or rethrow). Empty catch blocks are forbidden.
- **Try-with-resources** for every `AutoCloseable` — JDBC `Connection`, `PreparedStatement`, `ResultSet`, streams, sockets, HTTP bodies.
- Do not reassign method parameters.
- `e.printStackTrace()` is forbidden — route through SLF4J `log.error(...)` with structured context.
- Do not catch `Throwable` or `Exception` indiscriminately at adapter boundaries. Catch the narrow subtype the API actually throws (e.g. `SQLException` in `outbox-jdbc`).

### `var` keyword (§3.10)

- **Forbidden in production code**: `outbox-*/src/main/java/**`. Explicit types make reviews and cross-module navigation unambiguous, especially when the RHS is a builder, a stream pipeline, or a generic factory.
- Discouraged in tests when the type is not obvious from the RHS. Permitted for trivial literals (`var i = 42`), but explicit types remain the default.

### Imports (§3.11)

- **No wildcard imports** (`import foo.bar.*;`).
- **No unused imports** — Spotless `removeUnusedImports` enforces this.
- Group order, blank line between groups, alphabetical within each group:

  ```text
  import static ...;                          // 1. static imports

  import java.*;                              // 2. java
  import javax.*;                             // 3. javax
  import jakarta.*;                           // 4. jakarta
  import io.github.lobofoltran.outbox.*;      // 5. this library's own packages
  import <everything else alphabetical>;      // 6. everything else
  ```

## Tests

Aligned with the org-wide testing standard (§3.14). Rules below are enforced; violations fail the build.

### Frameworks

- **JUnit 5 only.** The following imports / runners are forbidden and rejected by static analysis:
  - `org.junit.Test`, `org.junit.Assert`, `org.junit.Before`, `org.junit.After`
  - `org.junit.Ignore`, `org.junit.Rule`, `org.junit.ClassRule`
  - `org.junit.runners.*`
  - `org.mockito.junit.MockitoJUnitRunner`
  - `org.springframework.test.context.junit4.SpringRunner`
- **AssertJ** for assertions. No `Assert.assertEquals` style.
- **Testcontainers (PostgreSQL)** for the `outbox-jdbc` integration tests. No JDBC mocking; tests run against a real PostgreSQL.

### Mockito (strict)

- Strict stubbing mode is on by default.
- Forbidden — strictness already detects unexpected stubs:
  - `never()`
  - `verifyNoInteractions(...)`
  - `verifyZeroInteractions(...)`
  - `verifyNoMoreInteractions(...)`
  Omit the `verify` call instead.
- `ArgumentMatchers.xxx(...)` as a qualified reference is forbidden — static-import the matchers.
- `mockito-inline` is **not** declared as a dependency. Do not mock static methods or final classes. If a collaborator can only be exercised through such a mock, redesign: inject a port and supply a fake or a Mockito mock of a non-final type.

### Test naming and layout

| Suffix | Purpose |
| --- | --- |
| `<Class>Test` | Unit test |
| `<Class>IT` | Integration test (Testcontainers, real PostgreSQL) |
| `<Class>ContractTest` | Consumer-driven contract test |
| `<Class>ReplayTest` | Saga replay test (not applicable in this repo, listed for parity) |

Tests mirror the source package layout exactly.

### No reflection, no visibility-for-testing

- **No reflection in tests.** Forbidden:
  - `Field.setAccessible(true)`, `Method.setAccessible(true)`, `Constructor.setAccessible(true)`
  - `org.springframework.test.util.ReflectionTestUtils`
  - Mockito legacy `Whitebox`
  - `MethodHandles.privateLookupIn(...)` used purely for visibility bypass
  - Any other mechanism that defeats Java access checks
- Tests exercise the public API of the type under test plus its declared ports.
- **No visibility-for-testing relaxation.** A field, method, constructor, or class MUST NOT be made `package-private`, `protected`, or `public` *only* to enable a test. Encapsulation is part of the design contract, not an obstacle to remove. If a behavior cannot be reached through the public API, refactor:
  - Extract a collaborator behind a port and inject a fake.
  - Hoist the use case to the application layer where it can be tested at the seam.
  - Rewrite the test to assert the observable side effect — e.g. *row persisted in `outbox`*, *INSERT participated in the caller's transaction*, *span recorded*.
  - Or accept that the path is not directly unit-testable and cover it via a component/integration test through the natural entry point.
- **No `@VisibleForTesting`-style annotations.** They normalize the anti-pattern and make widening visibility feel costless.
- **No mutation of `private static final` fields** at test time. No `sun.misc.Unsafe`. No test-time bytecode rewriting (PowerMock and equivalents forbidden by extension, since `mockito-inline` is also forbidden).

### Coverage thresholds

Org floor enforced by JaCoCo: **90% line and 90% branch at the bundle level** via `./mvnw -B -ntp -Pquality verify`. Lower per-module overrides via `coverage.line.minimum` / `coverage.branch.minimum` in the module POM require an **inline justification comment**. Raising above 90% per module is encouraged.

`outbox-core` is a contract module (interface + records). Org guidance bumps contract-like modules (`shared-kernel`, `event-contracts`) to **≥ 95%**, so the same target applies here.

| Module | Line | Branch | Mutation (PIT, project-level) | Notes |
| --- | --- | --- | --- | --- |
| `outbox-core` | ≥ 95% | ≥ 95% | ≥ 85% | Contract module — org guidance ≥95%. |
| `outbox-jdbc` | 100% | 100% | ≥ 90% | Real-PG IT covers the SQL paths; 100% is reachable without artifice. |
| `outbox-spring` | ≥ 90% | ≥ 90% | — (autoconfig) | Matches org floor. PIT not applied — autoconfig produces uninteresting mutants. |
| `outbox-micrometer` | ≥ 95% | ≥ 95% | ≥ 90% | Thin decorator; 100% is reachable. Higher than the floor on purpose. |
| `outbox-schema` | — | — | — | SQL resources only; no Java code to cover. |
| `outbox-bom` | — | — | — | POM only. |

PIT (mutation testing) is a project-level addition on top of the org JaCoCo floor — it is not in the org standard but is kept here because the JDBC mapper logic is exactly the kind of code line coverage can deceive on.

### Standardized JaCoCo / PIT exclusions

- `module-info`
- `**/*Exception` (unless it carries logic)
- Records with no custom methods (auto-generated accessors)
- Auto-generated `equals` / `hashCode` / `toString`

Exclusions beyond this list require an inline justification comment in `pom.xml`.

### Principles

- Branch coverage matters more than line coverage.
- Mutation testing is the source of truth; line coverage is a proxy.
- Forbidden: writing tests just to bump the number (`assertNotNull(new Foo())`).
- Forbidden: removing legitimate defensive code to "make coverage easier". If a branch is genuinely impossible to cover, add an annotated exclusion with justification in the PR.

## CI/CD

### Locked decisions

- **Artifact registry:** GitHub Packages (Maven). Migration to Maven Central is a v2 candidate if the library is consumed outside the org — GitHub Packages requires a PAT even for public packages, which adds friction.
- **Release automation:** [`release-please`](https://github.com/googleapis/release-please) driven by Conventional Commits. The bot keeps an open "release PR" that bumps `<revision>` and updates `CHANGELOG.md`; merging it creates the tag and GitHub Release.
- **Snapshots:** **not** published. Only tagged releases hit GitHub Packages. Consumers of bleeding-edge use JitPack against a SHA.
- **PIT (mutation testing):** runs on a **nightly schedule** in `main`. PR builds do **not** wait on PIT.
- **Postgres matrix:** Postgres 16 only. Other versions are not officially tested.
- **JDK matrix:** Java 25 only (matches the project minimum).
- **Build assignment of versions:** `${revision}` property in the parent POM + `flatten-maven-plugin`. `maven-release-plugin` is forbidden — it produces extra commits that conflict with Conventional Commits and branch protection.

### Workflows (`.github/workflows/`)

| Workflow | Trigger | Purpose |
| --- | --- | --- |
| `build.yml` | `pull_request`, `push` (any branch) | `./mvnw -B -ntp -Pquality verify` — build, tests (Testcontainers/PG16), JaCoCo gates. |
| `pit.yml` | `schedule` (nightly) + `workflow_dispatch` | PIT mutation testing. Reports in Job Summary; failures do **not** block PRs. |
| `release-please.yml` | `push` in `main` | Maintains the open release PR (CHANGELOG + `<revision>` bump). On merge, creates tag `vX.Y.Z` + GitHub Release. |
| `release.yml` | `push` on tag `v*.*.*` | Imports CI GPG key, runs `./mvnw -B -ntp -Prelease deploy`, attaches signed JARs to the GitHub Release. |
| `dependabot.yml` | weekly (config, not workflow) | Bumps for Maven deps + GitHub Actions. |

### Artifact signing

- The org-wide signed-commits rule applies to **commits and tags**.
- **Maven artifacts** are also GPG-signed in `release.yml` via `maven-gpg-plugin` (activated under the `release` profile).
- The signing key is a **CI-only** GPG key, separate from any contributor's personal key. Stored as a GitHub Secret; rotated annually.

### Required secrets

| Secret | Purpose |
| --- | --- |
| `GITHUB_TOKEN` | Provided by the runner. Publishes to GitHub Packages and drives release-please. |
| `GPG_PRIVATE_KEY` | Base64 of the CI signing key. |
| `GPG_PASSPHRASE` | Passphrase for `GPG_PRIVATE_KEY`. |

### Branch protection (configured in the GitHub UI)

- `main` requires:
  - One approving review
  - `build.yml` passing
  - All commits GPG-signed and verified
- Tags matching `v*` may only be created by the `release-please` bot or by maintainers.

### Consumer setup

Consumers must declare the GitHub Packages repository in `~/.m2/settings.xml` and use a PAT with `read:packages` scope. This friction is acknowledged; see [Locked decisions](#locked-decisions) for the Maven Central migration plan.

## Local development gotchas

Quirks discovered while bootstrapping the build. Read these before your first PR; they will save you 20 minutes each.

### Activate Java 25 per shell

Maven is installed on the typical contributor machine through SDKMAN with Java 21 as the default. The project requires Java 25 (enforced via `maven-enforcer-plugin`), so every fresh shell needs:

```bash
sdk use java 25.0.2-zulu     # or any 25.x distribution available locally
```

Without that, `./mvnw` fails at the enforcer step.

CI workflows use `actions/setup-java` and do not have this problem.

### Always go through `./mvnw`

Plugin versions are pinned and tested only against the wrapper-bundled Maven (currently 3.9.9). Calling a globally installed `mvn` may resolve different versions of `flatten-maven-plugin`, `spotless-maven-plugin`, etc. and produce confusing diffs.

### Spotless rewrites POMs aggressively (`sortPom`)

The `quality` profile runs `spotless:check` and will fail if a POM is not sorted into Spotless's canonical layout. Standard workflow when editing or adding a POM:

```bash
./mvnw -B -ntp -Pquality spotless:apply   # rewrites POMs and Java sources in place
./mvnw -B -ntp -Pquality verify           # then validate
```

Do not commit between `apply` and `verify` — review the diff first.

### Generating the Maven wrapper requires an existing parent POM

`mvn wrapper:wrapper` errors with `MavenProject.getCollectedProjects() is null` when run in an empty repo. Create the parent `pom.xml` first, then generate the wrapper. After that, switch to `./mvnw` for everything.

### PIT struggles with JPMS-modular modules

`pitest-maven` 1.23.1 runs cleanly against `outbox-core` (which is JPMS-modular but has no Testcontainers tests) but fails with `Coverage generation Minion exited abnormally (UNKNOWN_ERROR)` against `outbox-jdbc`. The combination of `module-info.java`, `--patch-module` test compilation, and PIT's minion process appears to be the trigger — the unit tests run fine through Surefire on the same module.

Mitigations in place:

- The `pit` profile excludes integration tests (`*IT`) so Testcontainers is not the issue.
- The nightly `pit.yml` workflow keeps `continue-on-error: true`; module failures do not stop the schedule.
- JaCoCo 100/100 line+branch via real-PG integration tests remains the source of truth for `outbox-jdbc` quality.

When PIT upstream lands proper module-path support, drop this note and re-enable strict mutation thresholds per `AGENTS > Test coverage`.

### Testcontainers on macOS Docker Desktop needs an explicit socket

Testcontainers' Docker auto-detection occasionally fails to find Docker Desktop's socket on macOS. If you see `Could not find a valid Docker environment` when running `./mvnw verify -pl outbox-jdbc`, set:

```bash
export DOCKER_HOST=unix:///Users/$(whoami)/.docker/run/docker.sock
```

before running Maven. CI runners (Linux) do not need this — they use the default `/var/run/docker.sock`.

### Restricted-method warnings from Maven 3.9.x on Java 25 are benign

`jansi`, `guava` and a few other Maven internals print warnings like `WARNING: A restricted method in java.lang.System has been called` and `sun.misc.Unsafe::objectFieldOffset will be removed in a future release`. They originate inside Maven itself, not the project, and disappear when Maven ships a JDK-25-friendly release. Filter them in shell pipelines if they hide errors:

```bash
./mvnw -B -ntp -Pquality verify 2>&1 | grep -vE '^WARNING:'
```

### Branch protection vs. AI workflow

`main` is configured to reject unsigned commits. Local config in this repo sets `commit.gpgsign=true` and `tag.gpgsign=true` automatically; if your signature still shows `Unverified` on GitHub, the email on the commit is not associated with a verified GPG key on your GitHub profile.

When working with an AI agent, after every code-producing turn the AI must amend its commits to include the `Co-Authored-By: Devin` trailer (see [Commit & PR rules](#commit--pr-rules)). If the AI forgets, the human reviewer should call it out before merging.

## Commit & PR rules

- **Conventional Commits** are mandatory. Allowed types: `feat`, `fix`, `docs`, `chore`, `refactor`, `test`, `build`, `ci`, `perf`.
- **Commit scope** is a Maven module name from this repo:
  - `outbox-core`, `outbox-jdbc`, `outbox-spring`, `outbox-micrometer`, `outbox-schema`, `outbox-bom`
  - or a cross-cutting scope: `build`, `ci`, `docs`, `repo`
  - Examples: `feat(outbox-jdbc): add UUIDv7 id generator`, `chore(repo): add CODEOWNERS`, `docs(outbox-core): document OutboxEvent fields`.
- **One logical change per PR.** Cross-cutting refactors (e.g. renaming a package across all modules) must be split into separate PRs per concern, or clearly justified in the description.
- **PR description MUST link to the ADR / PRD it implements.** When a change predates the ADR, the PR adds the ADR first, in a separate commit, and links it.
- **All commits and tags MUST be GPG-signed.** Signing keys are configured via `git config --local` only — the global git config is never modified by this project. Branch protection on `main` rejects unsigned commits.
- **Every commit authored with the help of an AI agent MUST carry a `Co-Authored-By` trailer for that agent.** This is non-optional and applies to amends and rebases too. The trailer block at the end of the commit message is:

  ```
  Generated with [Devin](https://cli.devin.ai/docs)

  Co-Authored-By: Devin <158243242+devin-ai-integration[bot]@users.noreply.github.com>
  ```

  When in doubt, re-attach via `git rebase main --exec 'add_trailer.sh'` and force-push the feature branch. **Never** force-push `main` without explicit confirmation in the conversation that produced the change.
- **PR template** requires:
  - Which stage of the roadmap this belongs to
  - Risk summary
  - Rollback plan
  - Observability impact (logs, metrics, traces affected)

### Repo-specific notes

- ADRs are stored under `docs/adr/`. ADR-0001 backfills the decisions captured in [Locked decisions](#locked-decisions).
- The PR template lives at `.github/pull_request_template.md`.
- A pre-commit / pre-push hook validating commit message format and GPG signature is **recommended** but not yet wired. Track as a `chore(repo)` issue when needed.
