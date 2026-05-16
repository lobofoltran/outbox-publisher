/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox.jdbc.internal;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.ServiceLoader;

import io.github.lobofoltran.outbox.OutboxConfigurationException;
import io.github.lobofoltran.outbox.jdbc.spi.OutboxDialect;
import io.github.lobofoltran.outbox.jdbc.spi.OutboxDialectProvider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves the {@link OutboxDialect} that matches a given {@link Connection}'s database. Iterates
 * over registered {@link OutboxDialectProvider}s, picks the highest-priority match, and creates the
 * dialect instance.
 *
 * <p>Decoupled from {@code JdbcOutbox} so it can be unit-tested without spinning up a database: the
 * constructor accepts the {@link Iterable} of providers, which production wires through {@link
 * ServiceLoader} and tests wire to a hand-rolled list.
 *
 * @since 0.1.0
 */
public final class DialectAutoDetector {

    private static final Logger LOG = LoggerFactory.getLogger(DialectAutoDetector.class);

    private final Iterable<OutboxDialectProvider> providers;

    public DialectAutoDetector(Iterable<OutboxDialectProvider> providers) {
        this.providers = providers;
    }

    /** Loads providers via {@link ServiceLoader} on the {@link OutboxDialect} class loader. */
    public static DialectAutoDetector usingServiceLoader() {
        return new DialectAutoDetector(
                ServiceLoader.load(
                        OutboxDialectProvider.class, OutboxDialect.class.getClassLoader()));
    }

    /**
     * Picks the matching provider with the highest priority and creates a dialect.
     *
     * @throws OutboxConfigurationException if no provider supports the database.
     * @throws SQLException if {@code connection.getMetaData()} fails.
     */
    public OutboxDialect detect(Connection connection) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        OutboxDialectProvider best = null;
        for (OutboxDialectProvider provider : providers) {
            try {
                if (!provider.supports(metaData)) {
                    continue;
                }
            } catch (SQLException ex) {
                LOG.debug(
                        "OutboxDialectProvider {} threw SQLException during supports(); skipping",
                        provider.getClass().getName(),
                        ex);
                continue;
            }
            if (best == null || provider.priority() > best.priority()) {
                best = provider;
            }
        }
        if (best == null) {
            String product = metaData.getDatabaseProductName();
            throw new OutboxConfigurationException(
                    "No OutboxDialect on the classpath supports database product '"
                            + product
                            + "'. Add a provider via ServiceLoader, or pass one to"
                            + " JdbcOutbox.builder().dialect(...).");
        }
        return best.create();
    }
}
