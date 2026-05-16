/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox.tck;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ServiceLoader;
import javax.sql.DataSource;

import io.github.lobofoltran.outbox.jdbc.spi.OutboxDialect;
import io.github.lobofoltran.outbox.jdbc.spi.OutboxDialectProvider;

import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Reference implementation of the {@code outbox-tck} contract suite. {@link PostgresDialect} runs
 * against a real PostgreSQL container and MUST pass 100% of {@link OutboxDialectContractTest}. This
 * is the canonical example for any external dialect author — copy this class, swap the dialect /
 * DataSource / DDL, and the same suite will validate the new dialect.
 *
 * <p>Co-located with the TCK rather than in {@code outbox-jdbc/src/test/java} because adding {@code
 * outbox-tck} (which depends on {@code outbox-jdbc}) as a test-scope dependency of {@code
 * outbox-jdbc} would create a reactor cycle. Placing the reference IT here lets the TCK
 * self-validate against the bundled dialect — a standard TCK pattern.
 *
 * <p>The dialect itself is resolved via {@link ServiceLoader} so this test only depends on the
 * exported SPI ({@code io.github.lobofoltran.outbox.jdbc.spi}) and never reaches into the dialect's
 * internal implementation package.
 */
@Testcontainers
class PostgresDialectContractIT extends OutboxDialectContractTest {

    @Container
    @SuppressWarnings("resource") // managed by the @Testcontainers extension
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine").withReuse(false);

    private static final String PUBLISHER_DDL = loadResource("/sql/postgres/outbox-publisher.sql");

    private final OutboxDialect dialect = loadBundledDialect();
    private final DataSource dataSource = newDataSource();

    @Override
    protected OutboxDialect dialect() {
        return dialect;
    }

    @Override
    protected DataSource dataSource() {
        return dataSource;
    }

    @Override
    protected void applyPublisherSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(PUBLISHER_DDL);
        }
    }

    private static OutboxDialect loadBundledDialect() {
        return ServiceLoader.load(OutboxDialectProvider.class).stream()
                .map(p -> p.get())
                .filter(provider -> provider.getClass().getName().contains("postgres"))
                .map(OutboxDialectProvider::create)
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "PostgresDialectProvider not on the test classpath"));
    }

    private static DataSource newDataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }

    private static String loadResource(String resource) {
        try (InputStream in = PostgresDialectContractIT.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + " not on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
