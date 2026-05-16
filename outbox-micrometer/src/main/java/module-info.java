/**
 * Optional Micrometer instrumentation for {@link io.github.lobofoltran.outbox.Outbox}. Add this
 * module to the classpath alongside {@code outbox-spring} (or wire {@link
 * io.github.lobofoltran.outbox.micrometer.MeteredOutbox} manually) to publish a {@code
 * outbox.publish} timer and a {@code outbox.publish.bytes} distribution summary on every event.
 * Cardinality policy is documented in ADR-0004.
 */
module io.github.lobofoltran.outbox.micrometer {
    requires transitive io.github.lobofoltran.outbox.core;
    requires micrometer.core;

    exports io.github.lobofoltran.outbox.micrometer;
}
