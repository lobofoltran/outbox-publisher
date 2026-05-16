# ADR-0001: Foundational decisions for outbox-publisher

- Status: accepted
- Date: 2026-05-16
- Deciders: @lobofoltran
- Tags: foundations, backfill

## Context and problem statement

The initial design of `outbox-publisher` was discussed and locked in `README.md` and `AGENTS.md` before any code was written. This ADR backfills those decisions into a single, citable record so that future PRs and ADRs can link to a stable reference.

Each section below states one decision, its rationale, the rejected alternatives, and its consequences. Subsequent ADRs (ADR-0002+) will refine or supersede individual sections as needed.

## Decisions

### D1 — Build tool: Apache Maven

**Decision:** Maven.

**Rationale:**

- Idiomatic in the broader Java library space (ShedLock, gruelbox, namastack, Spring projects). Lowers friction for external contributors.
- First-class support in `release-please` via `release-type: maven`.
- BOM authoring (`outbox-bom`) is a native concept; Gradle BOMs via Java Platform plugin are workable but more idiosyncratic.

**Rejected:** Gradle (Kotlin DSL). More flexible, but the cost of being non-standard does not pay off for a small multi-module library.

### D2 — JDK: Java 25 LTS

**Decision:** Java 25 (LTS).

**Rationale:**

- Mature records, pattern matching, virtual threads, sealed types — all useful in modeling `OutboxEvent` and the error hierarchy.
- LTS, so usable for production consumers without forcing an unstable JDK.

**Risks:** newer plugin versions required (JaCoCo 0.8.12+, ErrorProne 2.27+, Surefire 3.2.5+). Mitigation: validated during the build setup; Java 21 remains a viable fallback.

**Rejected:** Java 17 (older runtime, no virtual threads at scale), Java 21 (held in reserve as fallback).

### D3 — Database support in v1: PostgreSQL only

**Decision:** PostgreSQL ≥ 14 only.

**Rationale:**

- Reduces test surface (one Testcontainers image, one SQL dialect).
- `JSONB` and partial indexes are first-class in PostgreSQL and match the table contract.
- The largest real-world deployment target.

**Rejected:** MySQL 8 and Oracle. Will be re-evaluated for v2 if external adoption demands it.

### D4 — No Spring Data JPA

**Decision:** Plain JDBC. The implementation module is `outbox-jdbc`.

**Rationale:**

- The library must not impose a persistence layer on the consumer.
- The INSERT is on the hot path of every business transaction; JPA's entity manager, first-level cache, and flush timing add overhead and surprise without value here.
- Dialect-specific SQL (e.g. partial indexes, `FOR UPDATE SKIP LOCKED` on the relay side) is awkward to express in JPA.
- Precedent: ShedLock makes the same call.

**Rejected:** Spring Data JPA, Hibernate. Consumers may use them in their own code; the library does not.

### D5 — JPMS-modular pure modules

**Decision:** `outbox-core`, `outbox-jdbc`, and `outbox-micrometer` ship `module-info.java`. `outbox-spring` stays as an automatic module.

**Rationale:**

- Encapsulation is enforced by the compiler, not by convention. Consumers cannot reach into `outbox-jdbc` internals even by accident.
- ServiceLoader (`provides Outbox with JdbcOutbox`) makes the JDBC implementation pluggable at the module-path level.
- Spring Boot is not fully JPMS-modular, so forcing a `module-info.java` on `outbox-spring` creates more friction than it removes.

**Rejected:** classpath-only build. Loses the compile-time guarantees.

### D6 — No bundled migrations

**Decision:** `outbox-schema` ships a single SQL file (`outbox.sql`) as a classpath resource. The library never creates or alters the table.

**Rationale:**

- Every microservice already has a migration story (Flyway, Liquibase, hand-rolled). Imposing one of them creates conflicts.
- The DDL is small and stable; consumers can copy it verbatim.

**Rejected:** bundling Flyway or Liquibase as a runtime dependency.

### D7 — Logging: SLF4J, no implementation pinned

**Decision:** Modules log through SLF4J. No `logback`, `log4j`, or `slf4j-simple` declared in any runtime POM.

**Rationale:** the consumer owns the logging backend. Pinning one creates classpath fights.

**Rejected:** declaring an implementation in `outbox-spring` "for convenience".

### D8 — License: MIT

**Decision:** MIT.

**Rationale:**

- Permissive, short, widely understood.
- No patent clause needed for a library of this scope.

**Rejected:** Apache-2.0 (more verbose, patent clause adds nothing here for v1), GPL family (incompatible with intended use).

### D9 — Maven coordinates and root package

**Decision:**

- `groupId`: `io.github.lobofoltran`
- Root package: `io.github.lobofoltran.outbox`
- Modules: `outbox-core`, `outbox-jdbc`, `outbox-spring`, `outbox-micrometer`, `outbox-schema`, `outbox-bom`

**Rationale:**

- `io.github.*` is the canonical prefix for GitHub-hosted projects without a custom domain.
- Module names mirror those of ShedLock / gruelbox; recognizable to the target audience.

### D10 — Test coverage strategy

**Decision:**

- JaCoCo bundle-level floor: 90% line + 90% branch (org standard).
- `outbox-core` and `outbox-micrometer`: ≥ 95% line + 95% branch (contract / thin decorator).
- `outbox-jdbc`: 100% line + 100% branch (real-PG IT makes this reachable without artifice).
- PIT mutation testing as a project-level addition; runs on a nightly schedule, does not block PRs.

**Rationale:**

- Branch coverage is a stronger signal than line coverage.
- Mutation testing catches what line coverage hides, particularly in SQL mapping logic.
- Nightly PIT keeps PR builds fast.

**Rejected:** "100% in every module" — leads to trivial tests and removed defensive code. Documented as forbidden in `AGENTS.md > Tests > Principles`.

### D11 — Test framework rules

**Decision:**

- JUnit 5 only; JUnit 4 imports forbidden.
- Mockito strict mode; `never()` / `verifyNoInteractions(...)` and family forbidden.
- No reflection in tests; no `ReflectionTestUtils`; no `@VisibleForTesting`.
- No `mockito-inline`; no PowerMock; no static mocking.

**Rationale:** these rules force the code under test to expose its behavior through the public API or declared ports, which is the only sustainable testability strategy. They originate in the org-wide testing standard (§3.14).

### D12 — Coding standards

**Decision:** adopt org-wide §3.6–§3.11 — no boxing constructors, no `Arrays.asList`, no raw types, prefer records, try-with-resources for every `AutoCloseable`, `var` forbidden in production code, no wildcard imports.

**Rationale:** consistency across the org's Java codebases; cheap to enforce via Spotless + Checkstyle + ErrorProne.

### D13 — Release flow

**Decision:**

- `release-please` (Google's bot) driven by Conventional Commits.
- `${revision}` property + `flatten-maven-plugin` for single-source versioning.
- `maven-release-plugin` is forbidden — its extra commits conflict with Conventional Commits and branch protection.
- No SNAPSHOTs are published; only tagged releases.
- Consumers needing bleeding-edge use JitPack against a SHA.

**Rationale:**

- Conventional Commits + release-please makes versioning and changelog generation deterministic.
- SNAPSHOT pollution in GitHub Packages is hard to clean up; better to forbid it.

### D14 — Artifact registry

> Status: superseded in part by ADR-0003.

**Decision:** GitHub Packages (Maven).

**Rationale:** simplest path for an `io.github.*` project; integrates with the existing `GITHUB_TOKEN` in CI.

**Known cost:** consumers must configure a PAT in `~/.m2/settings.xml` even for public packages. Documented in the README. Migration to Maven Central is queued as a future ADR if external adoption justifies it.

### D15 — Commit and PR governance

**Decision:** Conventional Commits with module-name scopes (`outbox-core`, `outbox-jdbc`, `outbox-spring`, `outbox-micrometer`, `outbox-schema`, `outbox-bom`, plus cross-cutting `build`, `ci`, `docs`, `repo`). All commits and tags GPG-signed; signing configured via `git config --local` only. One logical change per PR. PR description links to the ADR or PRD it implements.

**Rationale:** the org standard, applied verbatim.

## Consequences

### Positive

- Every module of `outbox-publisher` has a documented, citable rationale before any line of code is written.
- Future ADRs can refine individual decisions (e.g. ADR-0002 may pin the `headers` serialization strategy) without rewriting this one.
- New contributors can read this single document to understand "why the project looks the way it looks".

### Negative

- This ADR is large. The trade-off is intentional: bundling foundational decisions avoids ADR sprawl during a phase where no code exists.
- Some decisions (e.g. D2, D10) may be revisited as the build matures; revisiting will happen via a focused ADR that explicitly supersedes the relevant section here.

## Links

- Operationalizes `AGENTS.md > Locked decisions`, `AGENTS.md > Coding standards`, `AGENTS.md > Tests`, `AGENTS.md > CI/CD`.
- Future related ADRs: ADR-0002 (headers serialization), ADR-0003 (Maven Central migration trigger), ADR-0004 (metrics cardinality policy).
