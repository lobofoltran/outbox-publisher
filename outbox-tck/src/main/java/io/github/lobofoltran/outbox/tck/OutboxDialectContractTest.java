/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox.tck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import javax.sql.DataSource;

import io.github.lobofoltran.outbox.OutboxConfigurationException;
import io.github.lobofoltran.outbox.OutboxDataException;
import io.github.lobofoltran.outbox.OutboxEvent;
import io.github.lobofoltran.outbox.OutboxIntegrityException;
import io.github.lobofoltran.outbox.OutboxTransientException;
import io.github.lobofoltran.outbox.jdbc.JdbcOutbox;
import io.github.lobofoltran.outbox.jdbc.spi.DialectCapability;
import io.github.lobofoltran.outbox.jdbc.spi.OutboxDialect;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Abstract JUnit 5 contract test base for {@link OutboxDialect} implementations.
 *
 * <p>External dialect authors extend this class, return their {@link OutboxDialect}, a {@link
 * DataSource} pointing at a real database (typically Testcontainers-backed), and a callback that
 * applies the publisher DDL on a fresh {@link Connection}. The full suite then verifies that the
 * dialect satisfies the runtime contract documented in ADR-0016 — covering happy-path inserts,
 * transactional semantics, autocommit refusal, SQLState classification, and capability-aware
 * idempotency / fallback behavior.
 *
 * <p>Capability-gated tests use {@link org.junit.jupiter.api.Assumptions} so a dialect that does
 * not advertise a capability is not penalized; a paired test asserts the documented fallback
 * behavior instead.
 *
 * <p>The class lives in {@code src/main/java} so it ships in {@code outbox-tck}'s main JAR. See
 * ADR-0016 for the rationale and the full enumeration of contract tests.
 *
 * @since 0.2.0
 */
public abstract class OutboxDialectContractTest {

    /** Subclass-only constructor; JUnit instantiates the concrete subclass per test method. */
    protected OutboxDialectContractTest() {}

    /** The dialect under test. Returned fresh per test or cached — implementation's choice. */
    protected abstract OutboxDialect dialect();

    /** A {@link DataSource} pointing at a database the dialect understands. */
    protected abstract DataSource dataSource();

    /**
     * Applies the publisher DDL (the {@code outbox} table the dialect writes into) on the supplied
     * {@link Connection}. The connection is configured by the TCK in autocommit mode, so the
     * implementation does not need to manage transactions itself.
     */
    protected abstract void applyPublisherSchema(Connection connection) throws SQLException;

    @BeforeEach
    final void recreateOutboxTable() throws SQLException {
        try (Connection connection = dataSource().getConnection()) {
            connection.setAutoCommit(true);
            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE IF EXISTS outbox");
            }
            applyPublisherSchema(connection);
        }
    }

    @AfterEach
    final void dropOutboxTable() throws SQLException {
        try (Connection connection = dataSource().getConnection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(true);
            statement.execute("DROP TABLE IF EXISTS outbox");
        }
    }

    // ---------- Happy path: minimal / maximal events --------------------------------------------

    @Test
    final void publish_persists_a_minimal_event() throws SQLException {
        UUID id = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-03-10T08:30:00Z");
        OutboxEvent event =
                OutboxEvent.builder()
                        .id(id)
                        .aggregateType("Order")
                        .aggregateId("ord-1")
                        .eventType("OrderPlaced")
                        .contentType("application/json")
                        .payload(new byte[] {1})
                        .occurredAt(occurredAt)
                        .build();

        publishInTransaction(event);

        try (Connection check = dataSource().getConnection();
                PreparedStatement statement =
                        check.prepareStatement(
                                "SELECT aggregate_type, aggregate_id, event_type, payload,"
                                    + " content_type, destination, occurred_at FROM outbox WHERE id"
                                    + " = ?")) {
            statement.setObject(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("aggregate_type")).isEqualTo("Order");
                assertThat(rs.getString("aggregate_id")).isEqualTo("ord-1");
                assertThat(rs.getString("event_type")).isEqualTo("OrderPlaced");
                assertThat(rs.getBytes("payload")).containsExactly(1);
                assertThat(rs.getString("content_type")).isEqualTo("application/json");
                assertThat(rs.getString("destination")).isNull();
                assertThat(rs.getTimestamp("occurred_at").toInstant()).isEqualTo(occurredAt);
            }
        }
    }

    @Test
    final void publish_persists_a_maximal_event() throws SQLException {
        UUID id = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-03-10T08:30:00Z");
        OutboxEvent event =
                OutboxEvent.builder()
                        .id(id)
                        .aggregateType("Order")
                        .aggregateId("ord-42")
                        .eventType("OrderPlaced")
                        .contentType("application/json")
                        .payload("{\"orderId\":42}".getBytes())
                        .headers(Map.of("tenant-id", "acme", "trace-id", "abc"))
                        .destination("orders.events")
                        .occurredAt(occurredAt)
                        .build();

        publishInTransaction(event);

        try (Connection check = dataSource().getConnection();
                PreparedStatement statement =
                        check.prepareStatement(
                                "SELECT aggregate_type, aggregate_id, event_type, payload,"
                                    + " content_type, destination, occurred_at FROM outbox WHERE id"
                                    + " = ?")) {
            statement.setObject(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("aggregate_type")).isEqualTo("Order");
                assertThat(rs.getString("aggregate_id")).isEqualTo("ord-42");
                assertThat(rs.getString("event_type")).isEqualTo("OrderPlaced");
                assertThat(rs.getBytes("payload")).isEqualTo("{\"orderId\":42}".getBytes());
                assertThat(rs.getString("content_type")).isEqualTo("application/json");
                assertThat(rs.getString("destination")).isEqualTo("orders.events");
                assertThat(rs.getTimestamp("occurred_at").toInstant()).isEqualTo(occurredAt);
            }
        }
    }

    // ---------- Null-safety ---------------------------------------------------------------------

    @Test
    final void publish_rejects_a_null_event() throws SQLException {
        try (Connection connection = dataSource().getConnection()) {
            connection.setAutoCommit(false);
            JdbcOutbox outbox = newOutbox(connection);
            assertThatNullPointerException().isThrownBy(() -> outbox.publish(null));
            connection.rollback();
        }
    }

    // ---------- Timezone round-trip -------------------------------------------------------------

    @Test
    final void timezone_round_trip_preserves_instant() throws SQLException {
        Instant occurredAt = Instant.parse("2026-03-10T08:30:00Z");
        UUID id = UUID.randomUUID();
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of("America/Sao_Paulo")));
            publishInTransaction(eventWith(id, occurredAt));

            TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of("UTC")));
            try (Connection check = dataSource().getConnection();
                    PreparedStatement statement =
                            check.prepareStatement("SELECT occurred_at FROM outbox WHERE id = ?")) {
                statement.setObject(1, id);
                try (ResultSet rs = statement.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    Instant readBack =
                            rs.getObject("occurred_at", OffsetDateTime.class).toInstant();
                    assertThat(readBack).isEqualTo(occurredAt);
                }
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }

    // ---------- Idempotency on duplicate id (capability-gated) ---------------------------------

    @Test
    final void publish_is_idempotent_on_duplicate_id_when_upsert_capability_is_advertised()
            throws SQLException {
        assumeTrue(
                dialect().capabilities().contains(DialectCapability.UPSERT_ON_CONFLICT),
                "Dialect does not advertise UPSERT_ON_CONFLICT — covered by the paired fallback"
                        + " test.");
        UUID id = UUID.randomUUID();
        OutboxEvent event = eventWith(id, Instant.parse("2026-03-10T08:30:00Z"));

        try (Connection connection = dataSource().getConnection()) {
            connection.setAutoCommit(false);
            JdbcOutbox outbox = newOutbox(connection);
            outbox.publish(event);
            outbox.publish(event); // duplicate id MUST be silently absorbed
            connection.commit();
        }

        assertThat(countOutboxRows()).isOne();
    }

    @Test
    final void publish_throws_integrity_exception_on_duplicate_id_when_upsert_is_absent()
            throws SQLException {
        assumeFalse(
                dialect().capabilities().contains(DialectCapability.UPSERT_ON_CONFLICT),
                "Dialect advertises UPSERT_ON_CONFLICT — covered by the paired idempotency test.");
        UUID id = UUID.randomUUID();
        OutboxEvent event = eventWith(id, Instant.parse("2026-03-10T08:30:00Z"));

        try (Connection connection = dataSource().getConnection()) {
            connection.setAutoCommit(false);
            JdbcOutbox outbox = newOutbox(connection);
            outbox.publish(event);
            assertThatThrownBy(() -> outbox.publish(event))
                    .isInstanceOf(OutboxIntegrityException.class);
            connection.rollback();
        }
    }

    // ---------- SQLState translation table ------------------------------------------------------

    @Test
    final void translates_unique_violation_to_integrity_exception() {
        assertThat(dialect().translate(new SQLException("dup", "23505"), "ctx"))
                .isInstanceOf(OutboxIntegrityException.class);
    }

    @Test
    final void translates_serialization_failure_to_transient_exception() {
        assertThat(dialect().translate(new SQLException("retry", "40001"), "ctx"))
                .isInstanceOf(OutboxTransientException.class);
    }

    @Test
    final void translates_string_too_long_to_data_exception() {
        assertThat(dialect().translate(new SQLException("too long", "22001"), "ctx"))
                .isInstanceOf(OutboxDataException.class);
    }

    @Test
    final void translates_undefined_column_to_configuration_exception() {
        assertThat(dialect().translate(new SQLException("missing column", "42703"), "ctx"))
                .isInstanceOf(OutboxConfigurationException.class);
    }

    // ---------- Autocommit refusal --------------------------------------------------------------

    @Test
    final void publish_rejects_an_autocommit_connection() throws SQLException {
        try (Connection connection = dataSource().getConnection()) {
            connection.setAutoCommit(true);
            JdbcOutbox outbox = newOutbox(connection);
            assertThatThrownBy(() -> outbox.publish(eventWith(UUID.randomUUID(), Instant.now())))
                    .isInstanceOf(OutboxConfigurationException.class)
                    .hasMessageContaining("autocommit");
        }
    }

    // ---------- publishAll ----------------------------------------------------------------------

    @Test
    final void publish_all_persists_every_event_in_the_batch() throws SQLException {
        OutboxEvent first = eventWith(UUID.randomUUID(), Instant.parse("2026-03-10T08:30:00Z"));
        OutboxEvent second = eventWith(UUID.randomUUID(), Instant.parse("2026-03-10T08:30:01Z"));
        OutboxEvent third = eventWith(UUID.randomUUID(), Instant.parse("2026-03-10T08:30:02Z"));

        try (Connection connection = dataSource().getConnection()) {
            connection.setAutoCommit(false);
            newOutbox(connection).publishAll(List.of(first, second, third));
            connection.commit();
        }

        assertThat(countOutboxRows()).isEqualTo(3);
    }

    @Test
    final void publish_all_rolls_back_with_the_callers_transaction() throws SQLException {
        OutboxEvent first = eventWith(UUID.randomUUID(), Instant.parse("2026-03-10T08:30:00Z"));
        OutboxEvent second = eventWith(UUID.randomUUID(), Instant.parse("2026-03-10T08:30:01Z"));

        try (Connection connection = dataSource().getConnection()) {
            connection.setAutoCommit(false);
            newOutbox(connection).publishAll(List.of(first, second));
            connection.rollback();
        }

        assertThat(countOutboxRows()).isZero();
    }

    // ---------- Helpers -------------------------------------------------------------------------

    private JdbcOutbox newOutbox(Connection connection) {
        return JdbcOutbox.builder().connectionSupplier(() -> connection).dialect(dialect()).build();
    }

    private void publishInTransaction(OutboxEvent event) throws SQLException {
        try (Connection connection = dataSource().getConnection()) {
            connection.setAutoCommit(false);
            newOutbox(connection).publish(event);
            connection.commit();
        }
    }

    private long countOutboxRows() throws SQLException {
        try (Connection check = dataSource().getConnection();
                PreparedStatement statement =
                        check.prepareStatement("SELECT count(*) FROM outbox");
                ResultSet rs = statement.executeQuery()) {
            assertThat(rs.next()).isTrue();
            return rs.getLong(1);
        }
    }

    private static OutboxEvent eventWith(UUID id, Instant occurredAt) {
        return OutboxEvent.builder()
                .id(id)
                .aggregateType("Order")
                .aggregateId(id.toString())
                .eventType("OrderPlaced")
                .contentType("application/json")
                .payload(new byte[] {1})
                .occurredAt(occurredAt)
                .build();
    }
}
