/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox.jdbc.internal;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 */
public final class DialectAutoDetector {

    private static final Logger LOG = LoggerFactory.getLogger(DialectAutoDetector.class);

    private final Iterable<OutboxDialectProvider> providers;

    public DialectAutoDetector(Iterable<OutboxDialectProvider> providers) {
        this.providers = providers;
    }

    /**
     * Loads providers via {@link ServiceLoader} cascading three class loaders, in priority order:
     *
     * <ol>
     *   <li>The library's own class loader (the one that loaded {@link OutboxDialect}). This is the
     *       normal path on flat classpaths.
     *   <li>The thread context class loader, if set and distinct. Needed for OSGi, Tomcat
     *       container-vs-{@code WEB-INF/lib}, and JBoss modules, where third-party providers loaded
     *       by the application class loader are invisible to the library's class loader.
     *   <li>The system class loader, if distinct. Final fallback for environments where neither of
     *       the above sees the providers.
     * </ol>
     *
     * <p>Discoveries are deduplicated by {@link Object#getClass() provider.getClass()} so a
     * provider visible through more than one class loader is loaded once. The selection rule in
     * {@link #detect(Connection)} (highest {@link OutboxDialectProvider#priority()} wins) is
     * unchanged; ties are broken by discovery order, which is deterministic given the cascade.
     */
    public static DialectAutoDetector usingServiceLoader() {
        ClassLoader libraryLoader = OutboxDialect.class.getClassLoader();
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        ClassLoader systemLoader = ClassLoader.getSystemClassLoader();

        List<OutboxDialectProvider> providers = new ArrayList<>();
        Set<Class<?>> seen = new HashSet<>();
        addProviders(providers, seen, libraryLoader);
        if (contextLoader != null && contextLoader != libraryLoader) {
            addProviders(providers, seen, contextLoader);
        }
        if (systemLoader != null && systemLoader != libraryLoader && systemLoader != contextLoader) {
            addProviders(providers, seen, systemLoader);
        }
        return new DialectAutoDetector(providers);
    }

    private static void addProviders(
            List<OutboxDialectProvider> providers,
            Set<Class<?>> seen,
            ClassLoader classLoader) {
        ServiceLoader<OutboxDialectProvider> loader =
                ServiceLoader.load(OutboxDialectProvider.class, classLoader);
        for (OutboxDialectProvider provider : loader) {
            if (seen.add(provider.getClass())) {
                providers.add(provider);
            }
        }
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
