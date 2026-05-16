package io.github.lobofoltran.outbox.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.UUID;

import io.github.lobofoltran.outbox.OutboxEvent;

import org.junit.jupiter.api.Test;

class JdbcOutboxIdempotencyIT extends AbstractPostgresIT {

    @Test
    void publishing_the_same_id_twice_results_in_a_single_row_and_does_not_throw()
            throws Exception {
        UUID id = UUID.randomUUID();
        OutboxEvent event = eventWith(id);

        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            JdbcOutbox outbox = JdbcOutbox.builder().connectionSupplier(() -> connection).build();
            outbox.publish(event);
            outbox.publish(event); // duplicate id — must be silently absorbed
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
    void publish_all_with_a_duplicate_in_the_same_batch_writes_one_row() throws Exception {
        UUID dup = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            JdbcOutbox.builder()
                    .connectionSupplier(() -> connection)
                    .build()
                    .publishAll(
                            java.util.List.of(eventWith(dup), eventWith(dup), eventWith(other)));
            connection.commit();
        }

        try (Connection check = openConnection();
                PreparedStatement statement =
                        check.prepareStatement("SELECT count(*) FROM outbox");
                ResultSet rs = statement.executeQuery()) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(2);
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
