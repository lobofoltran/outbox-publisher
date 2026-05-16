# ADR-0003: Maven Central migration (deferred)

- Status: deferred
- Date: 2026-05-16
- Deciders: @lobofoltran
- Tags: ci, distribution, registry

## Context and problem statement

ADR-0001 D14 locked the artifact registry to GitHub Packages. The known cost
of that decision is that consumers — even of a public package — must configure
a Personal Access Token in `~/.m2/settings.xml` to resolve dependencies. This
is friction we accepted while the library is consumed only inside the
organization. The question is *when* to migrate to Maven Central, not *if*.

## Decision drivers

- Adopter friction: the PAT requirement is the single biggest onboarding
  hurdle for an external consumer.
- Sunk cost on signing: ADR-0014 already requires GPG-signed Maven artifacts
  via a CI-only key, which is exactly what Sonatype/Maven Central requires.
- Release cadence stability: a registry migration is easier to perform once
  release-please and the release pipeline have been observed to work for
  several minor releases.
- Reversibility: moving from GitHub Packages to Maven Central is a one-way
  door for any given coordinate, since clients pin the registry implicitly via
  resolution order.

## Considered options

- Option A — Migrate to Maven Central now, before any external adoption.
- Option B — Stay on GitHub Packages indefinitely.
- Option C — Defer migration until a concrete trigger condition is met.

## Decision outcome

Chosen option: **Option C**, because the cost of staying on GitHub Packages is
known and bounded (it falls only on adopters), while the cost of migrating
prematurely is wasted release-engineering effort plus the irreversibility risk
above.

The library stays on GitHub Packages until **either** of the following is
observed, whichever comes first:

1. The first external bug report or feedback citing the PAT requirement as a
   blocker for adoption.
2. The v1.0.0 cut.

When the trigger fires, a new ADR (ADR-XX, with a date) will schedule and
sequence the actual migration. This ADR does not schedule it.

The pre-requisite work for Maven Central — GPG-signed artifacts via a CI-only
key — is already covered by ADR-0014 and is in place.

### Positive consequences

- No release-engineering churn until there is real demand for it.
- Signing pipeline stays exercised on every release, so the migration ADR will
  not need to redo that work.

### Negative consequences

- Every external adopter pays the PAT cost in the meantime.
- The migration backlog item must be revisited at v1.0.0 even if no external
  bug report has fired by then.

## Pros and cons of the options

### Option A — Migrate now

- Good, because adopters never have to configure a PAT.
- Bad, because there are no external adopters yet, so the migration is
  speculative.
- Bad, because Maven Central coordinates are effectively permanent; mistakes
  are expensive.

### Option B — Stay on GitHub Packages indefinitely

- Good, because zero migration effort.
- Bad, because the friction is unbounded as adoption grows.
- Bad, because v1.0.0 should be on a registry that does not require a PAT.

### Option C — Defer with a trigger

- Good, because cost is paid only when there is evidence of demand or at the
  v1.0.0 milestone.
- Good, because the trigger is observable, not subjective.
- Bad, because the project must remember to revisit at v1.0.0.

## Links

- Supersedes in part: ADR-0001 D14 (Artifact registry).
- Related: ADR-0014 (CI signing) — the GPG-signing pre-requisite is satisfied.
