/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox.jdbc.spi;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;

import io.github.lobofoltran.outbox.OutboxEvent;
import io.github.lobofoltran.outbox.OutboxException;

/**
 * Dialect SPI for {@code outbox-jdbc}. A dialect encapsulates every database-specific decision the
 * publisher must make: the {@code INSERT} SQL (including the idempotency clause), the column-type
 * bindings for non-trivial JDBC types ({@code UUID}, {@code JSON/JSONB}, {@code TIMESTAMP WITH
 * TIMEZONE}), and the translation from {@link SQLException} to a typed {@link OutboxException}
 * subtype.
 *
 * <p>Implementations are auto-discovered through {@link OutboxDialectProvider} via {@link
 * java.util.ServiceLoader}. Applications may also pass an explicit dialect to the {@code
 * JdbcOutbox} builder to bypass auto-detection.
 *
 * <p>Implementations must be thread-safe — a single dialect instance is shared across every {@code
 * publish} / {@code publishAll} call on the {@code JdbcOutbox} that resolved it. The {@link
 * OutboxInsert} handles the dialect returns from {@link #prepareInsert} are <em>not</em> required
 * to be thread-safe; a fresh handle is acquired for every call.
 *
 * @since 0.1.0
 */
public interface OutboxDialect {

    /**
     * Prepares an {@link OutboxInsert} handle for the given table. The dialect owns the underlying
     * {@link java.sql.PreparedStatement} — including the SQL text, the idempotency clause, and the
     * column-type bindings — and exposes only the {@link OutboxInsert} surface to the publisher.
     *
     * <p>The returned handle is bound to {@code connection}; the publisher is responsible for
     * closing it via try-with-resources before the connection commits.
     *
     * @param connection the JDBC connection the statement will be prepared on; never {@code null}.
     * @param table the target outbox table; never {@code null}.
     * @return a fresh {@link OutboxInsert} handle the publisher will drive for one batch.
     * @throws SQLException if preparing the statement fails.
     * @since 0.2.0
     */
    OutboxInsert prepareInsert(Connection connection, TableRef table) throws SQLException;

    /**
     * Translates a JDBC failure into a typed {@link OutboxException} subtype, following a SQLState
     * classification scheme that maps integrity violations, transient connectivity errors, data
     * errors, and configuration errors onto the appropriate sealed subtype.
     *
     * @param ex the original {@link SQLException}; never {@code null}.
     * @param contextMessage human-readable context to include in the exception message.
     * @return the matching {@link OutboxException} subtype.
     */
    OutboxException translate(SQLException ex, String contextMessage);

    /**
     * Returns the dialect's optional capabilities.
     *
     * @return the set of supported {@link DialectCapability} values.
     */
    Set<DialectCapability> capabilities();

    /**
     * Validates that {@code event} fits this dialect's underlying column constraints (width, byte
     * length, character set, etc.) and throws {@link
     * io.github.lobofoltran.outbox.OutboxDataException OutboxDataException} otherwise. Called by
     * {@code JdbcOutbox} immediately before binding each row so callers get a fail-fast error
     * before the SQL is ever sent.
     *
     * <p>The default implementation is a no-op so existing dialect implementations remain source
     * compatible. PostgreSQL's dialect overrides this to enforce the byte-length limits of the
     * reference {@code outbox} schema (see {@code AGENTS.md > Table contract}). Adopters running a
     * customized schema with wider columns can either skip the {@code outbox-jdbc} default dialect
     * or supply their own {@link OutboxDialect} that relaxes the check.
     *
     * <p>Since 0.4.0 width/range failures surface as {@link
     * io.github.lobofoltran.outbox.OutboxDataException} (a leaf of the sealed {@link
     * io.github.lobofoltran.outbox.OutboxException} hierarchy) instead of {@link
     * IllegalArgumentException}, so callers can rely on a single {@code catch (OutboxException e)}
     * block.
     *
     * @param event the event about to be persisted; never {@code null}.
     * @throws io.github.lobofoltran.outbox.OutboxDataException if a field violates this dialect's
     *     constraints.
     * @since 0.3.0
     */
    default void validate(OutboxEvent event) {
        // Default: no dialect-specific checks. The record already enforces null/blank invariants.
    }
}
