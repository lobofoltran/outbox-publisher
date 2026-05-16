/**
 * Optional OpenTelemetry tracing for {@link io.github.lobofoltran.outbox.Outbox}. Add this module
 * to the classpath alongside {@code outbox-spring} (or wire {@link
 * io.github.lobofoltran.outbox.otel.TracedOutbox} manually) to start a {@code messaging.*} span on
 * every {@code publish} / {@code publishAll}. Cardinality and attribute policy is documented in
 * ADR-0014.
 */
module io.github.lobofoltran.outbox.otel {
    requires transitive io.github.lobofoltran.outbox.core;
    requires transitive io.opentelemetry.api;
    requires io.opentelemetry.context;

    exports io.github.lobofoltran.outbox.otel;
}
