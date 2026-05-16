/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox;

import java.util.Objects;

/**
 * Writes domain events to the outbox table inside the caller's database transaction.
 *
 * <p>The single method {@link #publish(OutboxEvent)} is the entire public contract of this library.
 * The implementation participates in whichever transaction is currently active on the caller's
 * thread; it does not start one of its own and it does not commit.
 *
 * <p>Implementations are obtained either through the autoconfiguration in {@code outbox-spring},
 * which exposes a Spring bean of type {@code Outbox}, or by directly instantiating the JDBC
 * implementation through its builder ({@code JdbcOutbox.builder()...build()}). {@code outbox-core}
 * deliberately does not expose any service-loader or factory hook for {@code Outbox} itself —
 * dialect-level pluggability inside {@code outbox-jdbc} is a separate, internal concern.
 *
 * <p>Implementations are expected to be thread-safe.
 *
 * @see OutboxEvent
 * @see OutboxException
 * @since 0.1.0
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
     * @since 0.1.0
     */
    void publish(OutboxEvent event);

    /**
     * Persists every event in {@code events} to the outbox table, atomically with respect to the
     * caller's transaction.
     *
     * <p>The default implementation simply iterates and calls {@link #publish(OutboxEvent)} per
     * event. Implementations are <em>encouraged</em> to override this with a true batch write (e.g.
     * JDBC {@code addBatch} / {@code executeBatch}) when the underlying store supports it. The
     * default exists for source compatibility — every {@link Outbox} produced before this method
     * was added still satisfies the contract.
     *
     * <p>If any individual write fails, the surrounding transaction will not be committed by this
     * library; the failure surfaces as an {@link OutboxException} subtype on the offending event.
     *
     * @param events the events to persist; never {@code null}, individual elements must not be
     *     {@code null}.
     * @throws NullPointerException if {@code events} or any element is {@code null}.
     * @throws OutboxException if the underlying store rejects any write.
     * @since 0.1.0
     */
    default void publishAll(Iterable<OutboxEvent> events) {
        Objects.requireNonNull(events, "events must not be null");
        for (OutboxEvent event : events) {
            publish(event);
        }
    }
}
