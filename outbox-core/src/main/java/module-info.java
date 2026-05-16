/**
 * Public API of the outbox-publisher library: the {@link io.github.lobofoltran.outbox.Outbox}
 * interface, the {@link io.github.lobofoltran.outbox.OutboxEvent} record, and the {@link
 * io.github.lobofoltran.outbox.OutboxException} type.
 *
 * <p>This module is intentionally minimal. Implementations live in {@code outbox-jdbc}; Spring Boot
 * wiring lives in {@code outbox-spring}; metrics live in {@code outbox-micrometer}. Consumers
 * depend only on this module at compile time.
 */
module io.github.lobofoltran.outbox.core {
    exports io.github.lobofoltran.outbox;

    uses io.github.lobofoltran.outbox.Outbox;
}
