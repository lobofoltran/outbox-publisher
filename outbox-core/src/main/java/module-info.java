/**
 * Public API of the outbox-publisher library: the {@link io.github.lobofoltran.outbox.Outbox}
 * interface, the {@link io.github.lobofoltran.outbox.OutboxEvent} record, and the {@link
 * io.github.lobofoltran.outbox.OutboxException} type.
 *
 * <p>This module is intentionally minimal. It exports only the public API and provides no {@link
 * java.util.ServiceLoader} hooks: {@code Outbox} is not discovered via service binding. Wiring is
 * the consumer's responsibility — either through Spring Boot autoconfiguration ({@code
 * outbox-spring}) or by explicit construction of an implementation from {@code outbox-jdbc}. The
 * {@code OutboxDialectProvider} SPI used internally by {@code outbox-jdbc} is declared in that
 * module, not here.
 *
 * <p>Implementations live in {@code outbox-jdbc}; Spring Boot wiring lives in {@code
 * outbox-spring}; metrics live in {@code outbox-micrometer}. Consumers depend only on this module
 * at compile time.
 */
module io.github.lobofoltran.outbox.core {
    exports io.github.lobofoltran.outbox;
}
