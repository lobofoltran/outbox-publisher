package com.example.plainjdbc;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import javax.sql.DataSource;

import io.github.lobofoltran.outbox.Outbox;
import io.github.lobofoltran.outbox.OutboxEvent;
import io.github.lobofoltran.outbox.jdbc.JdbcOutbox;

/**
 * Plain-JDBC adapter showing the full transaction lifecycle around
 * {@link Outbox#publish(OutboxEvent)}.
 *
 * <p>Key points illustrated by this class:
 *
 * <ul>
 *   <li>{@link JdbcOutbox} is built without {@code .dialect(...)} — the dialect
 *       is auto-detected on first publish via {@link java.util.ServiceLoader}.
 *   <li>The {@link io.github.lobofoltran.outbox.jdbc.ConnectionSupplier} returns
 *       the <strong>same</strong> {@code Connection} the business INSERT uses,
 *       via a {@link ThreadLocal}. That is what makes the two writes share one
 *       transaction.
 *   <li>{@code autoCommit} is flipped off explicitly. {@link JdbcOutbox} refuses
 *       to publish on an autocommit connection.
 *   <li>On any {@link RuntimeException} we {@code rollback()}; the outbox row is
 *       then guaranteed to be gone — exactly because it was never on a separate
 *       transaction.
 * </ul>
 */
public class OrderShipmentService {

    private final DataSource dataSource;
    private final Outbox outbox;
    private final ThreadLocal<Connection> currentConnection = new ThreadLocal<>();

    public OrderShipmentService(DataSource dataSource) {
        this.dataSource = dataSource;
        this.outbox =
                JdbcOutbox.builder()
                        .connectionSupplier(this::requireCurrentConnection)
                        // No .dialect(...) — PostgresDialect is picked up
                        // automatically through OutboxDialectProvider.
                        .build();
    }

    /** Exposed so tests can assert observable side effects through the public API. */
    public Outbox outbox() {
        return outbox;
    }

    /** Happy path: business write + outbox publish, committed atomically. */
    public void ship(UUID shipmentId, String orderId) {
        runInTransaction(
                connection -> {
                    insertShipment(connection, shipmentId, orderId);
                    outbox.publish(
                            OutboxEvent.builder()
                                    .aggregateType("Shipment")
                                    .aggregateId(shipmentId.toString())
                                    .eventType("ShipmentCreated")
                                    .destination("shipments.events")
                                    .contentType("application/json")
                                    .payload(
                                            ("{\"shipment\":\"" + shipmentId + "\",\"order\":\""
                                                            + orderId + "\"}")
                                                    .getBytes(StandardCharsets.UTF_8))
                                    .build());
                });
    }

    /**
     * Failure path used by the integration test: publishes the outbox row and
     * then throws. The whole transaction must roll back so the outbox row never
     * becomes visible.
     */
    public void shipAndFail(UUID shipmentId, String orderId) {
        runInTransaction(
                connection -> {
                    insertShipment(connection, shipmentId, orderId);
                    outbox.publish(
                            OutboxEvent.builder()
                                    .aggregateType("Shipment")
                                    .aggregateId(shipmentId.toString())
                                    .eventType("ShipmentCreated")
                                    .contentType("application/json")
                                    .payload("{}".getBytes(StandardCharsets.UTF_8))
                                    .build());
                    throw new IllegalStateException("simulated business failure");
                });
    }

    private void insertShipment(Connection connection, UUID shipmentId, String orderId)
            throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "INSERT INTO shipments (id, order_id) VALUES (?, ?)")) {
            statement.setObject(1, shipmentId);
            statement.setString(2, orderId);
            statement.executeUpdate();
        }
    }

    /**
     * Borrows a connection, flips autocommit off, runs {@code work} under the
     * thread-local, and commits — or rolls back on failure.
     */
    private void runInTransaction(JdbcWork work) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            currentConnection.set(connection);
            try {
                work.run(connection);
                connection.commit();
            } catch (RuntimeException | SQLException ex) {
                connection.rollback();
                if (ex instanceof RuntimeException re) {
                    throw re;
                }
                throw new IllegalStateException("transactional work failed", ex);
            } finally {
                currentConnection.remove();
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("could not borrow connection from pool", ex);
        }
    }

    private Connection requireCurrentConnection() {
        Connection connection = currentConnection.get();
        if (connection == null) {
            throw new IllegalStateException(
                    "No transactional Connection bound to the current thread."
                            + " OrderShipmentService.publish must be called inside ship(...).");
        }
        return connection;
    }

    @FunctionalInterface
    private interface JdbcWork {
        void run(Connection connection) throws SQLException;
    }
}
