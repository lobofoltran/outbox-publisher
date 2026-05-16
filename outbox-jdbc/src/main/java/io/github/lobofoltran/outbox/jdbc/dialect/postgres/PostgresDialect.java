/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox.jdbc.dialect.postgres;

import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import io.github.lobofoltran.outbox.OutboxConfigurationException;
import io.github.lobofoltran.outbox.OutboxDataException;
import io.github.lobofoltran.outbox.OutboxEvent;
import io.github.lobofoltran.outbox.OutboxException;
import io.github.lobofoltran.outbox.OutboxIntegrityException;
import io.github.lobofoltran.outbox.OutboxTransientException;
import io.github.lobofoltran.outbox.jdbc.spi.DialectCapability;
import io.github.lobofoltran.outbox.jdbc.spi.OutboxDialect;
import io.github.lobofoltran.outbox.jdbc.spi.TableRef;

/**
 * PostgreSQL implementation of {@link OutboxDialect}. Owns:
 *
 * <ul>
 *   <li>the idempotent {@code INSERT ... ON CONFLICT (id) DO NOTHING} SQL;
 *   <li>the {@code ?::jsonb} cast for the {@code headers} column;
 *   <li>the {@code TIMESTAMP WITH TIMEZONE} binding for {@code occurred_at} (so the database
 *       round-trips a true {@link Instant}, immune to JVM/session timezone changes);
 *   <li>the SQLState-to-{@link OutboxException} mapping documented in ADR-0008.
 * </ul>
 *
 * <p>This class is in an internal package and is <em>not</em> exported by the {@code outbox-jdbc}
 * module. Applications must only depend on the {@link OutboxDialect}/{@code OutboxDialectProvider}
 * SPI.
 *
 * @since 0.1.0
 */
public final class PostgresDialect implements OutboxDialect {

    // Width limits of the reference schema in outbox-schema/sql/postgres/outbox-publisher.sql.
    // Checked here as UTF-8 byte lengths — that is the upper bound on what PostgreSQL will store
    // even when VARCHAR(n) is character-counted, and it stays correct for adopters who run the
    // same column definitions on a byte-counted database via a different dialect.
    private static final int MAX_AGGREGATE_TYPE_BYTES = 128;
    private static final int MAX_AGGREGATE_ID_BYTES = 128;
    private static final int MAX_EVENT_TYPE_BYTES = 128;
    private static final int MAX_CONTENT_TYPE_BYTES = 64;
    private static final int MAX_DESTINATION_BYTES = 128;

    private static final Set<DialectCapability> CAPABILITIES =
            EnumSet.of(
                    DialectCapability.UPSERT_ON_CONFLICT,
                    DialectCapability.NATIVE_JSON,
                    DialectCapability.NATIVE_UUID,
                    DialectCapability.TIMESTAMP_WITH_TIMEZONE,
                    DialectCapability.BATCH_INSERT);

    @Override
    public String insertSql(TableRef table) {
        return "INSERT INTO "
                + table.qualified()
                + " ("
                + "id, aggregate_type, aggregate_id, event_type, payload, "
                + "content_type, headers, destination, occurred_at"
                + ") VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)"
                + " ON CONFLICT (id) DO NOTHING";
    }

    @Override
    public void bindId(PreparedStatement statement, int index, UUID id) throws SQLException {
        statement.setObject(index, id);
    }

    @Override
    public void bindHeaders(PreparedStatement statement, int index, String headersJson)
            throws SQLException {
        statement.setString(index, headersJson);
    }

    @Override
    public void bindTimestamp(PreparedStatement statement, int index, Instant value)
            throws SQLException {
        // setObject with an OffsetDateTime preserves the absolute instant (UTC offset) in
        // timestamptz columns, regardless of the JVM default timezone or the JDBC session
        // timezone. See ADR-0005.
        statement.setObject(
                index,
                OffsetDateTime.ofInstant(value, ZoneOffset.UTC),
                Types.TIMESTAMP_WITH_TIMEZONE);
    }

    @Override
    public void bindOptionalString(PreparedStatement statement, int index, String value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    @Override
    public OutboxException translate(SQLException ex, String contextMessage) {
        String state = ex.getSQLState();
        String message = contextMessage + " [SQLState=" + state + "]";
        if (state != null && state.length() == 5) {
            OutboxException byExact = translateExact(state, message, ex);
            if (byExact != null) {
                return byExact;
            }
        }
        String prefix = state == null ? "" : (state.length() >= 2 ? state.substring(0, 2) : state);
        return switch (prefix) {
            case "23" -> new OutboxIntegrityException(message, ex);
            case "08", "40" -> new OutboxTransientException(message, ex);
            case "22", "21" -> new OutboxDataException(message, ex);
            case "42", "53", "57", "58", "3D", "F0" ->
                    new OutboxConfigurationException(message, ex);
            default -> new OutboxException(message, ex);
        };
    }

    private static OutboxException translateExact(String state, String message, SQLException ex) {
        return switch (state) {
            // not_null_violation: schema column is required, caller violated schema contract.
            case "23502" -> new OutboxConfigurationException(message, ex);
            // PostgreSQL failover scenarios — recoverable on retry.
            case "57P01", "57P02", "57P03", "57014" -> new OutboxTransientException(message, ex);
            default -> null;
        };
    }

    @Override
    public Set<DialectCapability> capabilities() {
        return CAPABILITIES;
    }

    @Override
    public void validate(OutboxEvent event) {
        requireMaxBytes(event.aggregateType(), "aggregateType", MAX_AGGREGATE_TYPE_BYTES);
        requireMaxBytes(event.aggregateId(), "aggregateId", MAX_AGGREGATE_ID_BYTES);
        requireMaxBytes(event.eventType(), "eventType", MAX_EVENT_TYPE_BYTES);
        requireMaxBytes(event.contentType(), "contentType", MAX_CONTENT_TYPE_BYTES);
        if (event.destination() != null) {
            requireMaxBytes(event.destination(), "destination", MAX_DESTINATION_BYTES);
        }
    }

    private static void requireMaxBytes(String value, String name, int max) {
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > max) {
            throw new IllegalArgumentException(
                    name + " must be at most " + max + " bytes (UTF-8), got " + bytes + " bytes");
        }
    }
}
