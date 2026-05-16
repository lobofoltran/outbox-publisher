# ADR-0015: JVM target floor (open question, deferred to v1.0.0)

- Status: open question — deferred to v1.0.0 cut
- Date: 2026-05-16
- Deciders: @lobofoltran
- Tags: jvm, compatibility, distribution

## Context and problem statement

AGENTS.md and ADR-0001 lock the JVM target to **Java 25 (LTS)**. That matches
the internal services that originated this library. As soon as the library is
considered for adoption outside that perimeter, the question becomes whether
Java 25 is the right *minimum* — or whether the floor should be lowered to
Java 21 (the previous LTS) to match a broader installed base, while still
allowing the library to be **compiled** on Java 25.

This ADR records the trade-off and defers the decision to the v1.0.0 cut,
when adoption posture will be clearer.

## Decision drivers

- **Internal use:** all current consumers run Java 25. Lowering the floor
  buys nothing for them.
- **External adoption:** Java 21 is still the most widely deployed LTS in
  enterprise environments; Java 25 adoption is non-trivial.
- **Language features actually used in `src/main/java`:** the floor only has
  to be raised when a Java-25-only feature is in production code. Test code
  does not constrain the consumer's runtime.
- **Toolchain stability:** consuming a library compiled at `--release 21`
  from a Java 25 runtime is supported; the reverse is not.

## Considered options

- Option A — Keep the floor at Java 25.
- Option B — Lower the floor to Java 21 in the v1.0.0 cut, conditional on a
  source audit showing no Java-25-only feature is used in
  `outbox-*/src/main/java`.
- Option C — Lower the floor to Java 17 (the LTS before 21).

## Decision outcome

Chosen option: **deferred to the v1.0.0 cut.** Until then, the floor stays
**Java 25** as locked by ADR-0001.

The decision criterion at v1.0.0 is concrete:

> If a concrete Java-25-only language feature is used in `src/main/java` of
> any of `outbox-core`, `outbox-jdbc`, `outbox-otel`, `outbox-tck`, the floor
> stays at Java 25. Otherwise, the floor moves to Java 21 in the v1.0.0 cut.

Java 17 (Option C) is rejected up front because it loses sealed classes used
extensively in `outbox-core` (`OutboxException`) without any meaningful
adoption upside over Java 21.

### Audit (today)

Command run from the repository root:

```bash
rg --type java 'pattern matching|sealed|record pattern' outbox-*/src/main/java
```

Result (full output):

```
outbox-core/src/main/java/io/github/lobofoltran/outbox/OutboxException.java: * <p>This type is the root of a sealed hierarchy. Implementations (notably {@code outbox-jdbc})
outbox-core/src/main/java/io/github/lobofoltran/outbox/OutboxException.java:public sealed class OutboxException extends RuntimeException
```

Findings:

- **Sealed classes/interfaces:** one usage, `OutboxException` in
  `outbox-core`. Sealed classes are a finalized language feature since
  **Java 17**, available unchanged in Java 21. They do **not** by themselves
  require Java 25.
- **Pattern matching for `instanceof`:** none found.
- **Record patterns / pattern matching for `switch`:** none found.
- **Other Java-25-only language features in `src/main/java`:** none found.

On the basis of the audit alone, nothing in production code today blocks a
move to a Java 21 floor. The decision is still deferred to v1.0.0 because:

1. Between now and v1.0.0 we may legitimately introduce a Java-25-only
   feature, and it is preferable to evaluate the floor against the v1.0.0
   source tree, not today's.
2. Lowering the floor is a one-way door for any given major version, since
   it changes the published `Bundle-RequiredExecutionEnvironment` /
   `Multi-Release` posture and adopter expectations.

### Positive consequences

- The criterion is mechanical and re-runnable: the same `rg` command
  produces a yes/no answer at v1.0.0.
- Internal consumers are unaffected today.

### Negative consequences

- External adopters interested in evaluating the library before v1.0.0 are
  forced onto Java 25.
- The audit must be re-run as part of the v1.0.0 cut checklist; it cannot be
  forgotten.

## Pros and cons of the options

### Option A — Stay on Java 25

- Good, because matches all current consumers.
- Good, because lets the library use any Java 25 feature without ADR churn.
- Bad, because narrows external adoption to environments that have already
  moved to Java 25.

### Option B — Lower to Java 21 at v1.0.0 (conditional)

- Good, because matches the broadest LTS installed base.
- Good, because today's audit shows it is feasible.
- Bad, because the library cannot use Java-25-only features in production
  code without re-opening this ADR.

### Option C — Lower to Java 17

- Good, because broadest possible reach.
- Bad, because we already use sealed classes, which are fine on 17, but the
  ergonomics gap (records evolved a lot from 17 → 21) is not worth the extra
  reach over Java 21.
- Bad, because Java 17 is already past its peak deployment share.

## Links

- Related: ADR-0001 (Foundations) — locks Java 25 today.
- Reconsider at: v1.0.0 cut.
