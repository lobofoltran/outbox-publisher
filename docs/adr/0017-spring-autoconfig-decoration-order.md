# ADR-0017: Spring auto-config decoration order

- Status: accepted
- Date: 2026-05-16
- Deciders: @lobofoltran
- Tags: spring, autoconfig, observability

## Context and problem statement

`outbox-spring` registers two `BeanPostProcessor` beans that decorate every `Outbox` bean in the context:

- `MetricsConfiguration#outboxMetricsBeanPostProcessor` wraps with `MeteredOutbox` when Micrometer + a `MeterRegistry` bean are present.
- `TracingConfiguration#outboxTracingBeanPostProcessor` wraps with `TracedOutbox` when OpenTelemetry + an `OpenTelemetry` bean are present.

When both are eligible, the order in which the BPPs run determines the final wrapping. Until now both factories were unordered, leaving the order at the mercy of bean definition iteration order — a non-contractual detail that can flip across Spring Boot upgrades and is invisible to adopters reading the autoconfig source.

## Decision drivers

- **Operator experience trumps code-author convenience.** The user-observable boundary is the OpenTelemetry producer span. Trace-driven debugging starts there, so it must be the outermost layer.
- **Metrics belong inside the span.** ADR-0004 and ADR-0014 already pull in opposite directions on the cardinality of attributes vs labels; the practical contract is "the meter increment happens during the producer span". That is only true if the metrics decorator is closer to `JdbcOutbox` than the tracing decorator.
- **Determinism.** "Whichever order Spring happens to pick" is not a contract.
- **No coupling between decorator modules.** `outbox-otel` does not depend on `outbox-micrometer` and vice versa. The order has to be expressed at the wiring layer (the autoconfig), not inside either decorator.

## Considered options

- **D1 — Annotate both BPP `@Bean` factories with `@Order` so the metrics BPP runs first and the tracing BPP runs last.** Result: `TracedOutbox(MeteredOutbox(JdbcOutbox))`. Chosen.
- D2 — Compose the decorators inside the `outbox(...)` `@Bean` directly, no BPPs. Rejected: would break the symmetric extension model where any user-registered `Outbox` bean (not just the auto-configured one) is decorated. The BPP approach is what makes "register your own `@Bean Outbox` and still get metrics + tracing for free" work.
- D3 — Switch the order to `MeteredOutbox(TracedOutbox(JdbcOutbox))`. Rejected: a meter increment that happens *outside* the producer span breaks the mental model. Metric counters are recorded as if no tracing context existed for them, even though the work they represent absolutely happened during the span.

## Decision outcome

**D1.** Both BPPs implement `org.springframework.core.Ordered` directly:

| BPP class | `getOrder()` value | Runs |
| --- | --- | --- |
| `MetricsConfiguration.MetricsBeanPostProcessor` | `Ordered.LOWEST_PRECEDENCE - 200` | first |
| `TracingConfiguration.TracingBeanPostProcessor` | `Ordered.LOWEST_PRECEDENCE - 100` | last |

Spring runs ordered `BeanPostProcessor`s with the lower order value first. The metrics BPP therefore sees the bare `JdbcOutbox` and produces `MeteredOutbox(JdbcOutbox)`. The tracing BPP sees that result and produces `TracedOutbox(MeteredOutbox(JdbcOutbox))`. Both BPPs guard against double-wrapping with `instanceof` checks so a user-supplied `MeteredOutbox` or `TracedOutbox` short-circuits the corresponding step.

The order values use offsets from `LOWEST_PRECEDENCE` rather than absolute integers so that user-supplied BPPs can deterministically slot themselves before, after, or between our decorators by picking a closer or further offset.

### Why `Ordered` on the class, not `@Order` on the `@Bean` method

`PostProcessorRegistrationDelegate#registerBeanPostProcessors` partitions BPPs into priority-ordered, ordered, and non-ordered buckets via `beanFactory.isTypeMatch(name, Ordered.class)`. That call resolves the type from the bean *definition* — for a `static @Bean BeanPostProcessor xxx(...)` method, the declared return type is `BeanPostProcessor`, not `Ordered`, so the BPP lands in the non-ordered bucket and `@Order` on the factory method is silently ignored. The decoration order then becomes implementation-defined (in our environment: tracing first, metrics last — the opposite of what we want, with the meter increment happening outside the producer span).

Two fixes were considered: (1) annotate the BPPs with `@Order` *and* declare a more specific return type (`MetricsBeanPostProcessor` instead of `BeanPostProcessor`); (2) implement `Ordered` on the BPP class itself. We picked (2) because it survives careless refactors that widen the return type back to the interface, and because the order is now part of the BPP class's published contract — visible at the declaration site rather than buried under the `@Bean` method that creates it.

(For belt-and-braces, the `@Bean` factory method also returns the concrete class type so that `isTypeMatch(Ordered.class)` succeeds before the bean is instantiated. This is the difference between "Spring sorts the BPP correctly" and "Spring sorts it correctly only after instantiating it for ordering" — only the former actually wires up the right decoration order.)

### Positive consequences

- `TracedOutbox(MeteredOutbox(JdbcOutbox))` is now part of the public contract of `outbox-spring`. A test in `OutboxAutoConfigurationTest` asserts the exact stack.
- Metric increments are recorded inside the producer span. Trace-attached log correlation, exemplars (if Micrometer is wired to OTel), and span-scoped metric views all work as expected.
- The order is documented in the autoconfig javadoc and in this ADR — not in commit history, not in test comments.

### Negative consequences

- A future third decorator (e.g. resilience) requires a third `@Order` slot and another ADR amendment. Acceptable: decorators are public extension points and changing where they sit in the stack *should* require a deliberate decision.
- Adopters who relied on the previous (accidental) order will see a behavior change. Mitigation: the previous order was undocumented, so we treat this as a bug fix rather than a breaking change. Release notes call it out explicitly.

## Pros and cons of the options

### D1 — `@Order` on the BPPs

- **Pros.** Local. Idiomatic Spring. Easy to test via `ApplicationContextRunner`. Leaves user BPPs free to slot in.
- **Cons.** Relies on the reader knowing the "lower value first" rule for `BeanPostProcessor`s.

### D2 — Compose inside `outbox(...)` `@Bean`

- **Pros.** Order is a single, locally-readable `new TracedOutbox(new MeteredOutbox(...))` line.
- **Cons.** Loses the BPP's universality. A `@Bean Outbox` registered by the user would need to opt in by hand. Breaks the existing "drop in a tracer / a registry, the autoconfig handles the rest" UX.

### D3 — `MeteredOutbox(TracedOutbox(JdbcOutbox))`

- **Pros.** Slightly cheaper hot path on the meter side (no tracing context lookup before the increment).
- **Cons.** Operationally wrong. Meter increments are not associated with the producer span; debugging "this metric spiked, what was happening?" stops working through trace links.

## Links

- ADR-0004 — metrics and cardinality.
- ADR-0014 — tracing decorator with OpenTelemetry semantic conventions.
