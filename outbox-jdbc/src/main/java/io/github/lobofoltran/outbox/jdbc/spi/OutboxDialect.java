/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox.jdbc.spi;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

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
 * publish} / {@code publishAll} call on the {@code JdbcOutbox} that resolved it.
 *
 * @since 0.2.0
 */
public interface OutboxDialect {

    /**
     * Returns the parameterized {@code INSERT} SQL for the given table. The placeholder order is
     * fixed:
     *
     * <ol>
     *   <li>{@code id} — UUID
     *   <li>{@code aggregate_type} — String
     *   <li>{@code aggregate_id} — String
     *   <li>{@code event_type} — String
     *   <li>{@code payload} — byte[]
     *   <li>{@code content_type} — String
     *   <li>{@code headers} — JSON String (bound through {@link #bindHeaders})
     *   <li>{@code destination} — optional String (bound through {@link #bindOptionalString})
     *   <li>{@code occurred_at} — {@link Instant} (bound through {@link #bindTimestamp})
     * </ol>
     *
     * <p>The SQL is expected to be idempotent on duplicate {@code id} (e.g. PostgreSQL's {@code ON
     * CONFLICT (id) DO NOTHING}).
     */
    String insertSql(TableRef table);

    /** Binds {@code id} at the given parameter index. */
    void bindId(PreparedStatement statement, int index, UUID id) throws SQLException;

    /** Binds the {@code headers} JSON string at the given parameter index. */
    void bindHeaders(PreparedStatement statement, int index, String headersJson)
            throws SQLException;

    /**
     * Binds an {@link Instant} as a timezone-aware timestamp at the given parameter index. The
     * dialect is expected to preserve UTC instant equality across writer/reader timezone
     * configurations (see ADR-0005).
     */
    void bindTimestamp(PreparedStatement statement, int index, Instant value) throws SQLException;

    /**
     * Binds a nullable string at the given parameter index. Implementations are responsible for
     * picking the correct {@code Types.*} constant when the value is {@code null}.
     */
    void bindOptionalString(PreparedStatement statement, int index, String value)
            throws SQLException;

    /**
     * Translates a JDBC failure into a typed {@link OutboxException} subtype, following the
     * SQLState classification documented in ADR-0008.
     *
     * @param ex the original {@link SQLException}; never {@code null}.
     * @param contextMessage human-readable context to include in the exception message.
     */
    OutboxException translate(SQLException ex, String contextMessage);

    /** Returns the dialect's optional capabilities. */
    Set<DialectCapability> capabilities();

    /**
     * Validates that {@code event} fits this dialect's underlying column constraints (width, byte
     * length, character set, etc.) and throws {@link IllegalArgumentException} otherwise. Called by
     * {@code JdbcOutbox} immediately before binding each row so callers get a fail-fast error
     * before the SQL is ever sent.
     *
     * <p>The default implementation is a no-op so existing dialect implementations remain source
     * compatible. PostgreSQL's dialect overrides this to enforce the byte-length limits of the
     * reference {@code outbox} schema (see {@code AGENTS.md > Table contract}). Adopters running a
     * customized schema with wider columns can either skip the {@code outbox-jdbc} default dialect
     * or supply their own {@link OutboxDialect} that relaxes the check.
     *
     * @param event the event about to be persisted; never {@code null}.
     * @throws IllegalArgumentException if a field violates this dialect's constraints.
     * @since 0.3.0
     */
    default void validate(OutboxEvent event) {
        // Default: no dialect-specific checks. The record already enforces null/blank invariants.
    }
}
