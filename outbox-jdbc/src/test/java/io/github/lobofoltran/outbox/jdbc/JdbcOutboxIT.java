/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import io.github.lobofoltran.outbox.OutboxEvent;
import io.github.lobofoltran.outbox.OutboxException;
import io.github.lobofoltran.outbox.OutboxValidationException;

import org.junit.jupiter.api.Test;

class JdbcOutboxIT extends AbstractPostgresIT {

    @Test
    void persists_every_column_set_by_the_caller() throws Exception {
        Instant occurredAt = Instant.parse("2026-03-10T08:30:00Z");
        UUID id = UUID.fromString("01234567-89ab-7cde-8123-456789abcdef");
        OutboxEvent event =
                OutboxEvent.builder()
                        .id(id)
                        .aggregateType("Order")
                        .aggregateId("ord-42")
                        .eventType("OrderPlaced")
                        .contentType("application/json")
                        .payload("{\"orderId\":42}".getBytes())
                        .header("tenant-id", "acme")
                        .destination("orders.events")
                        .occurredAt(occurredAt)
                        .build();

        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            JdbcOutbox outbox = JdbcOutbox.builder().connectionSupplier(() -> connection).build();
            outbox.publish(event);
            connection.commit();
        }

        try (Connection check = openConnection();
                PreparedStatement statement =
                        check.prepareStatement(
                                "SELECT id, aggregate_type, aggregate_id, event_type, payload,"
                                        + " content_type, headers::text, destination, occurred_at,"
                                        + " status, attempts, next_attempt_at, published_at,"
                                        + " last_error, schema_version FROM outbox WHERE id = ?");
                ResultSet rs = bind(statement, id)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getObject("id", UUID.class)).isEqualTo(id);
            assertThat(rs.getString("aggregate_type")).isEqualTo("Order");
            assertThat(rs.getString("aggregate_id")).isEqualTo("ord-42");
            assertThat(rs.getString("event_type")).isEqualTo("OrderPlaced");
            assertThat(rs.getBytes("payload")).isEqualTo("{\"orderId\":42}".getBytes());
            assertThat(rs.getString("content_type")).isEqualTo("application/json");
            assertThat(rs.getString("headers")).isEqualTo("{\"tenant-id\": \"acme\"}");
            assertThat(rs.getString("destination")).isEqualTo("orders.events");
            assertThat(rs.getTimestamp("occurred_at").toInstant()).isEqualTo(occurredAt);
            assertThat(rs.getString("status")).isEqualTo("PENDING");
            assertThat(rs.getInt("attempts")).isZero();
            assertThat(rs.getTimestamp("next_attempt_at")).isNull();
            assertThat(rs.getTimestamp("published_at")).isNull();
            assertThat(rs.getString("last_error")).isNull();
            assertThat(rs.getShort("schema_version")).isEqualTo((short) 1);
            assertThat(rs.next()).isFalse();
        }
    }

    @Test
    void generates_uuidv7_when_caller_omits_id() throws Exception {
        Instant occurredAt = Instant.parse("2026-03-10T08:30:00Z");
        Clock fixed = Clock.fixed(occurredAt, ZoneOffset.UTC);
        OutboxEvent event =
                OutboxEvent.builder()
                        .aggregateType("Order")
                        .aggregateId("ord-43")
                        .eventType("OrderPlaced")
                        .contentType("application/json")
                        .payload(new byte[] {1})
                        .occurredAt(occurredAt)
                        .build();

        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            JdbcOutbox outbox =
                    JdbcOutbox.builder().connectionSupplier(() -> connection).clock(fixed).build();
            outbox.publish(event);
            connection.commit();
        }

        try (Connection check = openConnection();
                PreparedStatement statement = check.prepareStatement("SELECT id FROM outbox");
                ResultSet rs = statement.executeQuery()) {
            assertThat(rs.next()).isTrue();
            UUID generated = rs.getObject("id", UUID.class);
            assertThat(generated.version()).isEqualTo(7);
            long encodedMillis = generated.getMostSignificantBits() >>> 16;
            assertThat(encodedMillis).isEqualTo(occurredAt.toEpochMilli());
        }
    }

    @Test
    void writes_null_destination_when_event_destination_is_null() throws Exception {
        OutboxEvent event =
                OutboxEvent.builder()
                        .id(UUID.randomUUID())
                        .aggregateType("Order")
                        .aggregateId("ord-44")
                        .eventType("OrderPlaced")
                        .contentType("application/json")
                        .payload(new byte[] {1})
                        .destination(null)
                        .occurredAt(Instant.now())
                        .build();

        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            JdbcOutbox publisher =
                    JdbcOutbox.builder().connectionSupplier(() -> connection).build();
            publisher.publish(event);
            connection.commit();
        }

        try (Connection check = openConnection();
                PreparedStatement statement =
                        check.prepareStatement("SELECT destination FROM outbox");
                ResultSet rs = statement.executeQuery()) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("destination")).isNull();
            assertThat(rs.wasNull()).isTrue();
        }
    }

    @Test
    void respects_schema_qualifier_in_insert_sql() throws Exception {
        try (Connection setup = openConnection();
                java.sql.Statement statement = setup.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS app");
            statement.execute(
                    publisherDdl.replace("CREATE TABLE outbox", "CREATE TABLE app.outbox"));
        }

        OutboxEvent event =
                OutboxEvent.builder()
                        .id(UUID.randomUUID())
                        .aggregateType("Order")
                        .aggregateId("ord-45")
                        .eventType("OrderPlaced")
                        .contentType("application/json")
                        .payload(new byte[] {1})
                        .occurredAt(Instant.now())
                        .build();

        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            JdbcOutbox.builder()
                    .connectionSupplier(() -> connection)
                    .schema("app")
                    .build()
                    .publish(event);
            connection.commit();
        }

        try (Connection check = openConnection();
                PreparedStatement statement =
                        check.prepareStatement("SELECT count(*) FROM app.outbox");
                ResultSet rs = statement.executeQuery()) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }

        try (Connection cleanup = openConnection();
                java.sql.Statement statement = cleanup.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS app CASCADE");
        }
    }

    @Test
    void wraps_sql_exception_in_outbox_exception() throws Exception {
        // Drop the table after schema creation to force a SQL error path.
        try (Connection setup = openConnection();
                java.sql.Statement statement = setup.createStatement()) {
            statement.execute("DROP TABLE outbox");
        }

        OutboxEvent event =
                OutboxEvent.builder()
                        .id(UUID.randomUUID())
                        .aggregateType("Order")
                        .aggregateId("ord-46")
                        .eventType("OrderPlaced")
                        .contentType("application/json")
                        .payload(new byte[] {1})
                        .occurredAt(Instant.now())
                        .build();

        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            JdbcOutbox outbox = JdbcOutbox.builder().connectionSupplier(() -> connection).build();
            assertThatThrownBy(() -> outbox.publish(event))
                    .isInstanceOf(OutboxException.class)
                    .hasMessageContaining("failed to insert")
                    .hasCauseInstanceOf(SQLException.class);
            connection.rollback();
        }
    }

    @Test
    void wraps_connection_supplier_failure_in_outbox_exception() {
        ConnectionSupplier failing =
                () -> {
                    throw new SQLException("pool exhausted");
                };
        JdbcOutbox outbox = JdbcOutbox.builder().connectionSupplier(failing).build();

        OutboxEvent event =
                OutboxEvent.builder()
                        .aggregateType("Order")
                        .aggregateId("ord-47")
                        .eventType("OrderPlaced")
                        .contentType("application/json")
                        .payload(new byte[] {1})
                        .occurredAt(Instant.now())
                        .build();

        assertThatThrownBy(() -> outbox.publish(event))
                .isInstanceOf(OutboxException.class)
                .hasCauseInstanceOf(SQLException.class);
    }

    @Test
    void rejects_null_event() {
        JdbcOutbox outbox = JdbcOutbox.builder().connectionSupplier(() -> null).build();
        assertThatThrownBy(() -> outbox.publish(null))
                .isInstanceOf(OutboxValidationException.class);
    }

    private static ResultSet bind(PreparedStatement statement, UUID id) throws SQLException {
        statement.setObject(1, id);
        return statement.executeQuery();
    }
}
