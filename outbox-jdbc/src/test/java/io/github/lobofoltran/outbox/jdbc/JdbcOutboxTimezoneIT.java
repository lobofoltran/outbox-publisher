package io.github.lobofoltran.outbox.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.ZoneId;
import java.util.TimeZone;
import java.util.UUID;

import io.github.lobofoltran.outbox.OutboxEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Asserts that {@code occurred_at} survives a round-trip across mismatched JVM timezones,
 * exercising the dialect's {@code TIMESTAMP WITH TIMEZONE} binding (P0-1).
 */
class JdbcOutboxTimezoneIT extends AbstractPostgresIT {

    private final TimeZone originalDefault = TimeZone.getDefault();

    @AfterEach
    void restoreTimeZone() {
        TimeZone.setDefault(originalDefault);
    }

    @Test
    void instant_round_trips_when_writer_jvm_is_sao_paulo_and_reader_is_utc() throws Exception {
        Instant occurredAt = Instant.parse("2026-03-10T08:30:00Z");
        UUID id = UUID.randomUUID();

        // Writer JVM in America/Sao_Paulo: a timestamp WITHOUT TIME ZONE binding would
        // silently encode wall-clock time and the reader (UTC) would observe a 3-hour drift.
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of("America/Sao_Paulo")));
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            JdbcOutbox.builder()
                    .connectionSupplier(() -> connection)
                    .build()
                    .publish(
                            OutboxEvent.builder()
                                    .id(id)
                                    .aggregateType("Order")
                                    .aggregateId("ord-1")
                                    .eventType("OrderPlaced")
                                    .contentType("application/json")
                                    .payload(new byte[] {1})
                                    .occurredAt(occurredAt)
                                    .build());
            connection.commit();
        }

        TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of("UTC")));
        try (Connection check = openConnection();
                PreparedStatement statement =
                        check.prepareStatement("SELECT occurred_at FROM outbox WHERE id = ?")) {
            statement.setObject(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).isTrue();
                Instant readBack =
                        rs.getObject("occurred_at", java.time.OffsetDateTime.class).toInstant();
                assertThat(readBack).isEqualTo(occurredAt);
            }
        }
    }
}
