package io.github.lobofoltran.outbox.jdbc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Shared Testcontainers wiring for every IT in this module.
 *
 * <p>One PostgreSQL container is reused across the whole test class lifecycle; the {@code outbox}
 * table is recreated before each test so individual cases stay isolated without paying the cost of
 * a fresh container per test.
 */
@Testcontainers
abstract class AbstractPostgresIT {

    @Container
    @SuppressWarnings("resource") // managed by the @Testcontainers extension
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine").withReuse(false);

    protected static String ddl;

    @BeforeAll
    static void loadDdl() throws IOException {
        try (InputStream in =
                AbstractPostgresIT.class.getResourceAsStream("/sql/postgres/outbox.sql")) {
            if (in == null) {
                throw new IllegalStateException("/sql/postgres/outbox.sql not on the classpath");
            }
            ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @BeforeEach
    void recreateTable() throws SQLException {
        try (Connection connection = openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS outbox");
            statement.execute(ddl);
        }
    }

    @AfterEach
    void dropTable() throws SQLException {
        try (Connection connection = openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS outbox");
        }
    }

    protected static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
