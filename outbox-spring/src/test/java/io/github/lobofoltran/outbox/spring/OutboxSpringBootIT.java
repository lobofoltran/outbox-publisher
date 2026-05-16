package io.github.lobofoltran.outbox.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;

import io.github.lobofoltran.outbox.Outbox;
import io.github.lobofoltran.outbox.OutboxEvent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = OutboxSpringBootIT.TestApp.class)
@Testcontainers
class OutboxSpringBootIT {

    @Container
    @ServiceConnection
    @SuppressWarnings("resource") // lifecycle owned by the @Testcontainers extension
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private Outbox outbox;

    @Autowired private DataSource dataSource;

    @Autowired private TransactionTemplate transactionTemplate;

    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void recreateTable() throws SQLException, IOException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS outbox");
            statement.execute(loadResource("/sql/postgres/outbox-publisher.sql"));
            statement.execute(loadResource("/sql/postgres/outbox-relay-extension.sql"));
        }
    }

    private static String loadResource(String resource) throws IOException {
        try (InputStream in = OutboxSpringBootIT.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + " not on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void publishes_inside_managed_transaction_and_row_is_visible_after_commit() {
        UUID id = UUID.randomUUID();
        transactionTemplate.executeWithoutResult(status -> outbox.publish(eventWith(id)));
        assertThat(countById(id)).isEqualTo(1);
    }

    @Test
    void publish_is_rolled_back_when_managed_transaction_rolls_back() {
        UUID id = UUID.randomUUID();
        transactionTemplate.executeWithoutResult(
                status -> {
                    outbox.publish(eventWith(id));
                    status.setRollbackOnly();
                });
        assertThat(countById(id)).isZero();
    }

    @Test
    void publish_is_rolled_back_when_business_code_throws() {
        UUID id = UUID.randomUUID();
        try {
            transactionTemplate.executeWithoutResult(
                    status -> {
                        outbox.publish(eventWith(id));
                        throw new IllegalStateException("business failure");
                    });
        } catch (IllegalStateException expected) {
            // rollback triggered by the exception
        }
        assertThat(countById(id)).isZero();
    }

    private int countById(UUID id) {
        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM outbox WHERE id = ?", Integer.class, id);
        return count != null ? count : 0;
    }

    private static OutboxEvent eventWith(UUID id) {
        return OutboxEvent.builder()
                .id(id)
                .aggregateType("Order")
                .aggregateId("ord-1")
                .eventType("OrderPlaced")
                .contentType("application/json")
                .payload(new byte[] {1, 2, 3})
                .occurredAt(Instant.parse("2026-03-10T08:30:00Z"))
                .build();
    }

    @SpringBootApplication
    static class TestApp {}
}
