/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox.jdbc.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

import io.github.lobofoltran.outbox.jdbc.spi.OutboxDialect;
import io.github.lobofoltran.outbox.jdbc.spi.OutboxDialectProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that {@link DialectAutoDetector#usingServiceLoader()} discovers providers via the thread
 * context class loader (TCCL) when they are not declared on the library's own classpath.
 *
 * <p>The fake provider class lives in the test classpath (so it is loadable), but the {@code
 * META-INF/services/...OutboxDialectProvider} file declaring it is written to a temporary directory
 * exposed only through a {@link URLClassLoader} installed as the TCCL. The library class loader
 * therefore cannot reach the service declaration through {@link java.util.ServiceLoader} and must
 * fall back to the TCCL.
 */
class DialectAutoDetectorTcclTest {

    private static final String SERVICES_RESOURCE =
            "META-INF/services/io.github.lobofoltran.outbox.jdbc.spi.OutboxDialectProvider";

    @Test
    void tolerates_null_thread_context_class_loader() {
        // Belt-and-suspenders: Thread.getContextClassLoader() may return null when no TCCL has
        // been set. The cascade must skip nulls rather than NPE, and still discover the bundled
        // PostgresDialectProvider via the library's own class loader.
        ClassLoader savedTccl = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(null);
            assertThat(DialectAutoDetector.usingServiceLoader()).isNotNull();
        } finally {
            Thread.currentThread().setContextClassLoader(savedTccl);
        }
    }

    @Test
    void discovers_provider_only_visible_via_tccl(@TempDir Path tempDir) throws Exception {
        Path servicesFile = tempDir.resolve(SERVICES_RESOURCE);
        Files.createDirectories(servicesFile.getParent());
        Files.writeString(servicesFile, FakeTcclProvider.class.getName() + "\n");

        URL[] urls = {tempDir.toUri().toURL()};
        ClassLoader savedTccl = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader tccl = new URLClassLoader(urls, savedTccl)) {
            Thread.currentThread().setContextClassLoader(tccl);

            DialectAutoDetector detector = DialectAutoDetector.usingServiceLoader();
            OutboxDialect dialect = detector.detect(connection("FakeTcclDB"));

            assertThat(dialect).isSameAs(FakeTcclProvider.DIALECT);
        } finally {
            Thread.currentThread().setContextClassLoader(savedTccl);
        }
    }

    private static Connection connection(String productName) throws SQLException {
        Connection connection = mock(Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn(productName);
        return connection;
    }

    /**
     * Public so {@link java.util.ServiceLoader} can instantiate it, and {@code static} so it has
     * the implicit public no-arg constructor required by the service provider contract. The class
     * is intentionally <em>not</em> referenced from any {@code META-INF/services} resource on the
     * test classpath — the test installs that mapping via the temporary {@link URLClassLoader}.
     */
    public static final class FakeTcclProvider implements OutboxDialectProvider {

        static final OutboxDialect DIALECT = mock(OutboxDialect.class);

        @Override
        public boolean supports(DatabaseMetaData metaData) throws SQLException {
            return "FakeTcclDB".equals(metaData.getDatabaseProductName());
        }

        @Override
        public OutboxDialect create() {
            return DIALECT;
        }

        @Override
        public int priority() {
            return 100;
        }
    }
}
