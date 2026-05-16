# ADR-0002: Headers shape and serialization strategy

- Status: accepted
- Date: 2026-05-16
- Deciders: @lobofoltran
- Tags: api, contract, serialization

## Context and problem statement

`OutboxEvent` carries a `headers` map alongside the binary `payload`. Two decisions need to be locked before the public API ships:

1. **The shape of `headers`** in the Java API: `Map<String, String>` versus `Map<String, Object>` versus a richer multi-value type.
2. **The on-the-wire format** of `headers` in the `outbox.headers` JSONB column.

Both decisions affect the public contract: changing them later requires a `schema_version` bump and synchronized writer/reader updates (per ADR-0001 D6).

## Decision drivers

- **Predictability for the relay.** Whatever shape we pick is what the relay reads back. Complex types create awkward routing.
- **Zero external dependency in `outbox-core`.** ADR-0001 D7 keeps `outbox-core` SLF4J-only. Pulling Jackson into the core just for headers is disproportionate.
- **Wire interoperability.** A future non-Java publisher (Go, Python) must be able to read and write the same table.
- **CloudEvents / messaging convention.** Kafka, RabbitMQ, AMQP, NATS, SNS — all treat headers as string-keyed string values. Going broader than that creates impedance mismatch at the broker layer anyway.

## Considered options

### Headers shape

- **A1 — `Map<String, String>`** (chosen).
- A2 — `Map<String, Object>` with implicit Jackson serialization.
- A3 — `MultiValueMap<String, String>` (Spring-flavored).
- A4 — `List<Header>` records.

### Serialization

- **B1 — Hand-rolled JSON in `outbox-jdbc`** (chosen). A 60-line `HeadersJsonWriter` / `HeadersJsonReader` pair handles only `Map<String, String>`, escaping per RFC 8259.
- B2 — Jackson `databind` as a runtime dependency of `outbox-jdbc`.
- B3 — A pluggable `HeadersCodec` SPI.

## Decision outcome

**A1 + B1.** Public API is `Map<String, String>`. Serialization is hand-rolled JSON inside `outbox-jdbc`, hidden from the consumer.

### Why A1

- Matches every supported broker's native header model. No information is lost when the relay forwards events.
- Trivial to validate, copy, and reason about. The compact constructor of `OutboxEvent` can `Map.copyOf(headers)` and be done.
- `Map<String, Object>` invites accidental coupling to Jackson, custom serializers, and surprise `Date`/`Instant` formatting. We reject that complexity.
- Users with non-string metadata (numbers, timestamps) format it themselves and document the convention in their event schema.

### Why B1

- Keeps `outbox-core` at zero runtime dependencies besides `java.base`.
- Keeps `outbox-jdbc` at zero non-JDBC runtime dependencies. Adding Jackson here would force every consumer to ship Jackson at runtime even if they have no other use for it.
- The data being serialized is constrained to `Map<String, String>` — there is no need for Jackson's polymorphism, custom deserializers, or annotations.
- The implementation is 60 lines including tests, covered by unit and integration tests in F5.

### Positive consequences

- `outbox-core` stays SLF4J-only and `outbox-jdbc` stays JDBC-driver-only.
- A future Go or Python publisher writing the same column produces compatible JSON without coordinating Java types.
- API surface stays trivially small (`OutboxEvent.builder().header(k, v)`).

### Negative consequences

- Users wanting structured header values must encode them themselves (`header("retry-count", "3")`).
- Hand-rolled JSON code must be carefully tested for escaping, including embedded quotes and Unicode. ADR-0001 D11 (no reflection, no mocking of internals) means tests exercise the public path: write headers, read row, parse back via `org.postgresql` JSONB.

## Pros and cons of the rejected options

### A2 — `Map<String, Object>`

- Bad, because it leaks Jackson conventions into the public API; users pass `Date`, `Instant`, `BigDecimal` and get inconsistent strings back depending on Jackson config.
- Bad, because the relay must either re-serialize through Jackson or carry the raw bytes — both options leak Jackson into the relay too.

### A3 — `MultiValueMap`

- Good, because some brokers (HTTP, AMQP) allow repeated headers.
- Bad, because no broker we target in v1 actually requires multi-values; the cost is a heavier API for negligible payoff.

### A4 — `List<Header>` records

- Good, because it allows ordering.
- Bad, because ordering is not preserved through any of the brokers we target. Same payoff as A1 with extra boilerplate.

### B2 — Jackson `databind`

- Good, because it is the de-facto JSON library on the JVM.
- Bad, because pulling Jackson into a library that just needs to serialize a flat string-to-string map is over-spend. It also forces consumers into Jackson version conflicts.

### B3 — Pluggable `HeadersCodec`

- Good, because it leaves the door open for binary or non-JSON formats.
- Bad, because it is YAGNI for v1. If we ever need it, we can introduce the SPI without breaking `Map<String, String>`. Defer to a future ADR.

## Compatibility

- `schema_version = 1` from ADR-0001 D6 covers this format.
- Going from A1 to A2 in the future would be a breaking API change → new major version. Not planned.
- Going from B1 to B2 silently is acceptable as long as the produced JSON for `Map<String, String>` stays byte-compatible (i.e. UTF-8, RFC 8259, no key reordering surprises that affect equality on the relay side).

## Links

- Refines ADR-0001 D6 and D7.
- Operationalized in:
  - **F3** (this ADR's host): `OutboxEvent.headers()` returns `Map<String, String>`.
  - **F5**: `outbox-jdbc` ships the hand-rolled JSON writer/reader.
