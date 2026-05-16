/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.UUID;

import io.github.lobofoltran.outbox.OutboxEvent;

import org.junit.jupiter.api.Test;

/**
 * Asserts that {@code JdbcOutbox} resolves the PostgreSQL dialect through {@link
 * java.util.ServiceLoader} when the builder does not pin one explicitly. We assert via
 * <em>behavior</em> rather than introspection: a dialect with {@code UPSERT_ON_CONFLICT} capability
 * silently absorbs duplicate {@code id} writes, and only {@code PostgresDialect} provides that
 * behavior in the current provider set.
 */
class JdbcOutboxDialectAutoDetectionIT extends AbstractPostgresIT {

    @Test
    void publishing_a_duplicate_id_is_silently_absorbed_when_dialect_is_auto_detected()
            throws Exception {
        UUID id = UUID.randomUUID();
        OutboxEvent event = eventWith(id);

        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            // No .dialect(...) on the builder — ServiceLoader must find PostgresDialectProvider.
            JdbcOutbox outbox = JdbcOutbox.builder().connectionSupplier(() -> connection).build();
            outbox.publish(event);
            outbox.publish(event);
            connection.commit();
        }

        try (Connection check = openConnection();
                PreparedStatement statement =
                        check.prepareStatement("SELECT count(*) FROM outbox WHERE id = ?")) {
            statement.setObject(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
    }

    private static OutboxEvent eventWith(UUID id) {
        return OutboxEvent.builder()
                .id(id)
                .aggregateType("Order")
                .aggregateId(id.toString())
                .eventType("OrderPlaced")
                .contentType("application/json")
                .payload(new byte[] {1})
                .occurredAt(Instant.parse("2026-03-10T08:30:00Z"))
                .build();
    }
}
