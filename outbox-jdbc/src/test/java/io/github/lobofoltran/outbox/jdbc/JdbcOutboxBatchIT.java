package io.github.lobofoltran.outbox.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.github.lobofoltran.outbox.OutboxEvent;

import org.junit.jupiter.api.Test;

class JdbcOutboxBatchIT extends AbstractPostgresIT {

    @Test
    void publish_all_writes_every_event_in_one_round_trip() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();

        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            JdbcOutbox.builder()
                    .connectionSupplier(() -> connection)
                    .build()
                    .publishAll(List.of(eventWith(first), eventWith(second), eventWith(third)));
            connection.commit();
        }

        try (Connection check = openConnection();
                PreparedStatement statement =
                        check.prepareStatement("SELECT count(*) FROM outbox");
                ResultSet rs = statement.executeQuery()) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(3);
        }
    }

    @Test
    void publish_all_with_empty_iterable_is_a_noop() throws Exception {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            JdbcOutbox.builder().connectionSupplier(() -> connection).build().publishAll(List.of());
            connection.commit();
        }

        try (Connection check = openConnection();
                PreparedStatement statement =
                        check.prepareStatement("SELECT count(*) FROM outbox");
                ResultSet rs = statement.executeQuery()) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isZero();
        }
    }

    @Test
    void publish_all_rolls_back_with_caller_transaction() throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            JdbcOutbox.builder()
                    .connectionSupplier(() -> connection)
                    .build()
                    .publishAll(List.of(eventWith(id), eventWith(UUID.randomUUID())));
            connection.rollback();
        }

        try (Connection check = openConnection();
                PreparedStatement statement =
                        check.prepareStatement("SELECT count(*) FROM outbox");
                ResultSet rs = statement.executeQuery()) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isZero();
        }
    }

    @Test
    void publish_all_accepts_a_non_list_iterable() throws Exception {
        UUID id = UUID.randomUUID();
        Iterable<OutboxEvent> events = java.util.Set.of(eventWith(id));

        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            JdbcOutbox.builder().connectionSupplier(() -> connection).build().publishAll(events);
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

    @Test
    void publish_all_rejects_null_iterable() {
        JdbcOutbox outbox = JdbcOutbox.builder().connectionSupplier(() -> null).build();
        assertThatNullPointerException()
                .isThrownBy(() -> outbox.publishAll(null))
                .withMessageContaining("events");
    }

    @Test
    void publish_all_rejects_null_element_in_list() {
        JdbcOutbox outbox = JdbcOutbox.builder().connectionSupplier(() -> null).build();
        java.util.List<OutboxEvent> events = new java.util.ArrayList<>();
        events.add(eventWith(UUID.randomUUID()));
        events.add(null);
        assertThatNullPointerException().isThrownBy(() -> outbox.publishAll(events));
    }

    @Test
    void publish_all_rejects_null_element_in_non_list_iterable() {
        JdbcOutbox outbox = JdbcOutbox.builder().connectionSupplier(() -> null).build();
        Iterable<OutboxEvent> iterable =
                () ->
                        java.util.Arrays.asList(eventWith(UUID.randomUUID()), (OutboxEvent) null)
                                .iterator();
        assertThatNullPointerException().isThrownBy(() -> outbox.publishAll(iterable));
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
