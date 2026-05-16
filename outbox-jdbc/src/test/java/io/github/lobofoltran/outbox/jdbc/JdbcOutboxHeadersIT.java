package io.github.lobofoltran.outbox.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import io.github.lobofoltran.outbox.OutboxEvent;

import org.junit.jupiter.api.Test;

class JdbcOutboxHeadersIT extends AbstractPostgresIT {

    @Test
    void empty_headers_round_trip_to_empty_jsonb() throws Exception {
        UUID id = persist(Map.of());
        try (Connection check = openConnection();
                PreparedStatement statement =
                        check.prepareStatement(
                                "SELECT jsonb_typeof(headers), headers::text FROM outbox WHERE id ="
                                        + " ?")) {
            statement.setObject(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("object");
                assertThat(rs.getString(2)).isEqualTo("{}");
            }
        }
    }

    @Test
    void single_header_pair_round_trips() throws Exception {
        UUID id = persist(Map.of("tenant-id", "acme"));
        assertThat(readHeader(id, "tenant-id")).isEqualTo("acme");
    }

    @Test
    void multiple_headers_round_trip_with_distinct_values() throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("tenant-id", "acme");
        headers.put("trace-id", "abc123");
        headers.put("retry-count", "3");

        UUID id = persist(headers);

        assertThat(readHeader(id, "tenant-id")).isEqualTo("acme");
        assertThat(readHeader(id, "trace-id")).isEqualTo("abc123");
        assertThat(readHeader(id, "retry-count")).isEqualTo("3");
    }

    @Test
    void special_characters_survive_round_trip() throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("quoted", "value with \"quotes\"");
        headers.put("backslash", "C:\\Users\\Public");
        headers.put("newline", "line1\nline2");
        headers.put("unicode", "café 🙂");
        headers.put("control", "\u0001abc");

        UUID id = persist(headers);

        assertThat(readHeader(id, "quoted")).isEqualTo("value with \"quotes\"");
        assertThat(readHeader(id, "backslash")).isEqualTo("C:\\Users\\Public");
        assertThat(readHeader(id, "newline")).isEqualTo("line1\nline2");
        assertThat(readHeader(id, "unicode")).isEqualTo("café 🙂");
        assertThat(readHeader(id, "control")).isEqualTo("\u0001abc");
    }

    private UUID persist(Map<String, String> headers) throws Exception {
        UUID id = UUID.randomUUID();
        OutboxEvent event =
                OutboxEvent.builder()
                        .id(id)
                        .aggregateType("Order")
                        .aggregateId("ord-h")
                        .eventType("OrderPlaced")
                        .contentType("application/json")
                        .payload(new byte[] {1})
                        .headers(headers)
                        .occurredAt(Instant.parse("2026-03-10T08:30:00Z"))
                        .build();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            JdbcOutbox.builder().connectionSupplier(() -> connection).build().publish(event);
            connection.commit();
        }
        return id;
    }

    private static String readHeader(UUID id, String key) throws Exception {
        try (Connection connection = openConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT headers ->> ? FROM outbox WHERE id = ?")) {
            statement.setString(1, key);
            statement.setObject(2, id);
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getString(1);
            }
        }
    }
}
