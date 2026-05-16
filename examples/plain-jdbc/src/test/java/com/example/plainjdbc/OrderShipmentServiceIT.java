package com.example.plainjdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class OrderShipmentServiceIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static HikariDataSource dataSource;
    private static OrderShipmentService service;

    @BeforeAll
    static void setUp() throws IOException, SQLException {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        config.setMaximumPoolSize(4);
        dataSource = new HikariDataSource(config);

        applySchema();

        service = new OrderShipmentService(dataSource);
    }

    @AfterAll
    static void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void shipCommitsBusinessAndOutboxRowsTogether() throws SQLException {
        UUID shipmentId = UUID.randomUUID();

        service.ship(shipmentId, "order-1");

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs =
                        statement.executeQuery(
                                "SELECT count(*) FROM outbox WHERE aggregate_id = '"
                                        + shipmentId
                                        + "'")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs =
                        statement.executeQuery(
                                "SELECT count(*) FROM shipments WHERE id = '"
                                        + shipmentId
                                        + "'")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void rollbackErasesBothBusinessAndOutboxRows() throws SQLException {
        UUID shipmentId = UUID.randomUUID();

        assertThatThrownBy(() -> service.shipAndFail(shipmentId, "order-2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("simulated business failure");

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs =
                        statement.executeQuery(
                                "SELECT count(*) FROM outbox WHERE aggregate_id = '"
                                        + shipmentId
                                        + "'")) {
            rs.next();
            assertThat(rs.getInt(1))
                    .as("outbox row must be rolled back together with the business write")
                    .isZero();
        }
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs =
                        statement.executeQuery(
                                "SELECT count(*) FROM shipments WHERE id = '"
                                        + shipmentId
                                        + "'")) {
            rs.next();
            assertThat(rs.getInt(1)).isZero();
        }
    }

    private static void applySchema() throws IOException, SQLException {
        try (InputStream in =
                        OrderShipmentServiceIT.class.getResourceAsStream("/sql/schema.sql");
                Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            if (in == null) {
                throw new IllegalStateException("missing /sql/schema.sql on test classpath");
            }
            String script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String stmt : script.split(";")) {
                String trimmed = stmt.trim();
                if (!trimmed.isEmpty()) {
                    statement.execute(trimmed);
                }
            }
        }
    }
}
