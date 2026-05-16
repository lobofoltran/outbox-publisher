/**
 * Optional Micrometer instrumentation for {@link io.github.lobofoltran.outbox.Outbox}. Add this
 * module to the classpath alongside {@code outbox-spring} (or wire {@link
 * io.github.lobofoltran.outbox.micrometer.MeteredOutbox} manually) to publish a {@code
 * outbox.publish} timer and a {@code outbox.publish.bytes} distribution summary on every event.
 * Tags stay low-cardinality by design — neither {@code aggregate_id} nor {@code destination} is
 * emitted as a tag.
 */
module io.github.lobofoltran.outbox.micrometer {
    requires transitive io.github.lobofoltran.outbox.core;
    requires micrometer.core;

    exports io.github.lobofoltran.outbox.micrometer;
}
