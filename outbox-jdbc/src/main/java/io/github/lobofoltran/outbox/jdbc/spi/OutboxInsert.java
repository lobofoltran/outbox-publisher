/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox.jdbc.spi;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import io.github.lobofoltran.outbox.OutboxEvent;

/**
 * Per-batch insert handle returned by {@link OutboxDialect#prepareInsert}. The dialect owns the
 * underlying {@link PreparedStatement} and the parameter order; the publisher only ever sees the
 * {@link OutboxEvent} / resolved id surface defined here.
 *
 * <p>Lifecycle:
 *
 * <ol>
 *   <li>{@link OutboxDialect#prepareInsert} returns a handle bound to an open connection.
 *   <li>The publisher calls {@link #bind(OutboxEvent, UUID)} followed by {@link #addBatch()} once
 *       per event.
 *   <li>The publisher calls {@link #executeBatch()} once when the batch is complete.
 *   <li>The publisher always calls {@link #close()} via try-with-resources, even on failure.
 * </ol>
 *
 * <p>Implementations are <em>not</em> required to be thread-safe; a fresh handle is acquired for
 * every {@code publish} / {@code publishAll} call.
 *
 * @since 0.2.0
 */
public interface OutboxInsert extends AutoCloseable {

    /**
     * Binds the event to the statement using the dialect's chosen parameter order. The {@code
     * resolvedId} is the id the publisher elected to persist — either the one carried by the event
     * or a freshly generated UUID.
     */
    void bind(OutboxEvent event, UUID resolvedId) throws SQLException;

    /** Adds the currently-bound row to the batch. */
    void addBatch() throws SQLException;

    /** Executes the accumulated batch and returns the per-row update counts. */
    int[] executeBatch() throws SQLException;

    /** Releases the underlying {@link PreparedStatement}. */
    @Override
    void close() throws SQLException;
}
