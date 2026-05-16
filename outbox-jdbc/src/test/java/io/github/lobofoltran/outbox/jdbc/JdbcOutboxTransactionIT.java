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

class JdbcOutboxTransactionIT extends AbstractPostgresIT {

    @Test
    void insert_is_visible_after_commit() throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            JdbcOutbox.builder()
                    .connectionSupplier(() -> connection)
                    .build()
                    .publish(eventWith(id));
            connection.commit();
        }

        assertThat(countRows(id)).isEqualTo(1);
    }

    @Test
    void insert_is_rolled_back_with_the_caller_transaction() throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            JdbcOutbox.builder()
                    .connectionSupplier(() -> connection)
                    .build()
                    .publish(eventWith(id));
            connection.rollback();
        }

        assertThat(countRows(id)).isZero();
    }

    @Test
    void supports_savepoint_partial_rollback() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            JdbcOutbox outbox = JdbcOutbox.builder().connectionSupplier(() -> connection).build();

            outbox.publish(eventWith(first));
            java.sql.Savepoint savepoint = connection.setSavepoint("before-second");
            outbox.publish(eventWith(second));
            connection.rollback(savepoint);

            connection.commit();
        }

        assertThat(countRows(first)).isEqualTo(1);
        assertThat(countRows(second)).isZero();
    }

    private static OutboxEvent eventWith(UUID id) {
        return OutboxEvent.builder()
                .id(id)
                .aggregateType("Order")
                .aggregateId("ord-tx")
                .eventType("OrderPlaced")
                .contentType("application/json")
                .payload(new byte[] {1, 2, 3})
                .occurredAt(Instant.parse("2026-03-10T08:30:00Z"))
                .build();
    }

    private static int countRows(UUID id) throws Exception {
        try (Connection connection = openConnection();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT count(*) FROM outbox WHERE id = ?")) {
            statement.setObject(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
