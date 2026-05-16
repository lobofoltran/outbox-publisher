package io.github.lobofoltran.outbox.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class OutboxHealthIndicatorIT {

    @Container
    @SuppressWarnings("resource") // lifecycle owned by the @Testcontainers extension
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static HikariDataSource dataSource;

    @BeforeAll
    static void openPool() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(POSTGRES.getJdbcUrl());
        cfg.setUsername(POSTGRES.getUsername());
        cfg.setPassword(POSTGRES.getPassword());
        dataSource = new HikariDataSource(cfg);
    }

    @AfterAll
    static void closePool() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @BeforeEach
    void recreateTable() throws SQLException, IOException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS outbox");
            statement.execute(loadResource("/sql/postgres/outbox-publisher.sql"));
        }
    }

    @Test
    @DisplayName("reports up() with the qualified table name when the table exists")
    void reports_up_when_table_exists() {
        OutboxHealthIndicator indicator = new OutboxHealthIndicator(dataSource, null, "outbox");
        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("table", "outbox");
    }

    @Test
    @DisplayName("reports down() and surfaces the SQLException when the table is gone")
    void reports_down_when_table_missing() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE outbox");
        }
        OutboxHealthIndicator indicator = new OutboxHealthIndicator(dataSource, null, "outbox");
        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .as("AbstractHealthIndicator surfaces the cause under 'error'")
                .containsKey("error");
    }

    private static String loadResource(String resource) throws IOException {
        try (InputStream in = OutboxHealthIndicatorIT.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + " not on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
