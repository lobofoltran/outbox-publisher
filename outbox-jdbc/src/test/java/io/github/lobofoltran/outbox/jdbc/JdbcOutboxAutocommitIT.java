/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox.jdbc;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.github.lobofoltran.outbox.OutboxConfigurationException;
import io.github.lobofoltran.outbox.OutboxEvent;

import org.junit.jupiter.api.Test;

class JdbcOutboxAutocommitIT extends AbstractPostgresIT {

    @Test
    void publish_throws_configuration_exception_when_connection_is_in_autocommit()
            throws Exception {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(true);
            JdbcOutbox outbox = JdbcOutbox.builder().connectionSupplier(() -> connection).build();

            assertThatThrownBy(() -> outbox.publish(event()))
                    .isInstanceOf(OutboxConfigurationException.class)
                    .hasMessageContaining("autocommit");
        }
    }

    @Test
    void publish_all_throws_configuration_exception_when_connection_is_in_autocommit()
            throws Exception {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(true);
            JdbcOutbox outbox = JdbcOutbox.builder().connectionSupplier(() -> connection).build();

            assertThatThrownBy(() -> outbox.publishAll(List.of(event())))
                    .isInstanceOf(OutboxConfigurationException.class)
                    .hasMessageContaining("autocommit");
        }
    }

    private static OutboxEvent event() {
        return OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateType("Order")
                .aggregateId("ord-1")
                .eventType("OrderPlaced")
                .contentType("application/json")
                .payload(new byte[] {1})
                .occurredAt(Instant.parse("2026-03-10T08:30:00Z"))
                .build();
    }
}
