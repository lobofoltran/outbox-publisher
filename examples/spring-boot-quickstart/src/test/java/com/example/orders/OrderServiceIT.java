package com.example.orders;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end integration test: places an order and asserts that
 *
 * <ul>
 *   <li>the {@code orders} row landed in the same transaction, and
 *   <li>a row landed in the {@code outbox} table with the expected aggregate metadata.
 * </ul>
 *
 * Uses Spring Boot's {@code @ServiceConnection} support so the Testcontainers
 * Postgres is wired into the auto-configured {@code DataSource}/{@code Flyway} without
 * a single manual property override.
 */
@SpringBootTest
@Testcontainers
class OrderServiceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private OrderService orderService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void publishesOutboxRowInsideBusinessTransaction() {
        Order order =
                new Order(UUID.randomUUID(), "customer-42", new BigDecimal("19.95"));

        orderService.place(order);

        Long orderRows =
                jdbc.queryForObject(
                        "SELECT count(*) FROM orders WHERE id = ?", Long.class, order.id());
        assertThat(orderRows).isEqualTo(1L);

        String aggregateId =
                jdbc.queryForObject(
                        "SELECT aggregate_id FROM outbox WHERE event_type = 'OrderPlaced'"
                                + " AND aggregate_id = ?",
                        String.class,
                        order.id().toString());
        assertThat(aggregateId).isEqualTo(order.id().toString());

        String destination =
                jdbc.queryForObject(
                        "SELECT destination FROM outbox WHERE aggregate_id = ?",
                        String.class,
                        order.id().toString());
        assertThat(destination).isEqualTo("orders.events");
    }
}
