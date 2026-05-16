/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox.spring;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import javax.sql.DataSource;

import io.github.lobofoltran.outbox.jdbc.spi.TableRef;

import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;

/**
 * Spring Boot Actuator {@link AbstractHealthIndicator} that probes the outbox table.
 *
 * <p>The probe is intentionally cheap: it opens a {@link Connection} from the configured {@link
 * DataSource} and runs {@code SELECT 1 FROM <schema>.<table> WHERE 1 = 0}. The query returns no
 * rows but does require the table to exist and the JDBC user to have {@code SELECT} on it, so it
 * exercises both schema presence and permissions without scanning data.
 *
 * <p>Identifier safety mirrors {@code JdbcOutbox} — the schema and table names are validated via
 * {@link TableRef} before being interpolated into the SQL string. Any value that does not match the
 * conservative identifier pattern is rejected at construction time.
 *
 * <p>Reports {@code up()} with a {@code table} detail on success; reports {@code down()} carrying
 * the {@link SQLException} cause on failure.
 *
 * @since 0.2.0
 */
public class OutboxHealthIndicator extends AbstractHealthIndicator {

    private final DataSource dataSource;
    private final TableRef table;
    private final String probeSql;

    /**
     * Creates a new health indicator.
     *
     * @param dataSource the {@link DataSource} the probe opens connections from; never {@code
     *     null}.
     * @param schema the outbox schema, or {@code null}/blank for an unqualified table.
     * @param tableName the outbox table name.
     * @since 0.2.0
     */
    public OutboxHealthIndicator(DataSource dataSource, String schema, String tableName) {
        super("outbox health check failed");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        String resolvedSchema = (schema == null || schema.isBlank()) ? null : schema;
        this.table = new TableRef(resolvedSchema, tableName);
        this.probeSql = "SELECT 1 FROM " + table.qualified() + " WHERE 1 = 0";
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(probeSql);
            builder.up().withDetail("table", table.qualified());
        }
    }
}
