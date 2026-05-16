/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox.jdbc.dialect.postgres;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import io.github.lobofoltran.outbox.OutboxEvent;
import io.github.lobofoltran.outbox.jdbc.internal.HeadersJsonWriter;
import io.github.lobofoltran.outbox.jdbc.spi.OutboxInsert;

/**
 * PostgreSQL implementation of {@link OutboxInsert}. Owns the parameter order and the column-type
 * bindings for the {@code outbox} insert: {@code UUID}, JSONB headers, optional {@code VARCHAR}
 * destination, and {@code TIMESTAMP WITH TIME ZONE} {@code occurred_at} bound as an {@link
 * OffsetDateTime} so the absolute instant survives JVM/session timezone differences (see ADR-0005).
 */
final class PostgresOutboxInsert implements OutboxInsert {

    private final PreparedStatement statement;

    PostgresOutboxInsert(PreparedStatement statement) {
        this.statement = statement;
    }

    @Override
    public void bind(OutboxEvent event, UUID resolvedId) throws SQLException {
        statement.setObject(1, resolvedId);
        statement.setString(2, event.aggregateType());
        statement.setString(3, event.aggregateId());
        statement.setString(4, event.eventType());
        statement.setBytes(5, event.payload());
        statement.setString(6, event.contentType());
        statement.setString(7, HeadersJsonWriter.toJson(event.headers()));
        if (event.destination() == null) {
            statement.setNull(8, Types.VARCHAR);
        } else {
            statement.setString(8, event.destination());
        }
        // setObject with an OffsetDateTime preserves the absolute instant (UTC offset) in
        // timestamptz columns, regardless of the JVM default timezone or the JDBC session
        // timezone. See ADR-0005.
        statement.setObject(
                9,
                OffsetDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC),
                Types.TIMESTAMP_WITH_TIMEZONE);
    }

    @Override
    public void addBatch() throws SQLException {
        statement.addBatch();
    }

    @Override
    public int[] executeBatch() throws SQLException {
        return statement.executeBatch();
    }

    @Override
    public void close() throws SQLException {
        statement.close();
    }
}
