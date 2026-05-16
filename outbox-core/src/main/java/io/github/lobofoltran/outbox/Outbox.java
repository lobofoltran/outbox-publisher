package io.github.lobofoltran.outbox;

/**
 * Writes domain events to the outbox table inside the caller's database transaction.
 *
 * <p>The single method {@link #publish(OutboxEvent)} is the entire public contract of this library.
 * The implementation participates in whichever transaction is currently active on the caller's
 * thread; it does not start one of its own and it does not commit.
 *
 * <p>Implementations are obtained through {@link java.util.ServiceLoader} or, in Spring Boot
 * applications, through the autoconfiguration in {@code outbox-spring}.
 *
 * <p>Implementations are expected to be thread-safe.
 *
 * @see OutboxEvent
 * @see OutboxException
 */
public interface Outbox {

    /**
     * Persists the given event to the outbox table.
     *
     * <p>The write is performed inside the caller's active transaction and is rolled back together
     * with the caller's business writes if that transaction aborts.
     *
     * @param event the event to persist; never {@code null}.
     * @throws NullPointerException if {@code event} is {@code null}.
     * @throws OutboxException if the underlying store rejects the write.
     */
    void publish(OutboxEvent event);
}
