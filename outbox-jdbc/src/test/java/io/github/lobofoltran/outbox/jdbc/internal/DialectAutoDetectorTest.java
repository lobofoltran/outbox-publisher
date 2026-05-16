package io.github.lobofoltran.outbox.jdbc.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.List;

import io.github.lobofoltran.outbox.OutboxConfigurationException;
import io.github.lobofoltran.outbox.jdbc.spi.OutboxDialect;
import io.github.lobofoltran.outbox.jdbc.spi.OutboxDialectProvider;

import org.junit.jupiter.api.Test;

class DialectAutoDetectorTest {

    private final OutboxDialect dialectA = mock(OutboxDialect.class);
    private final OutboxDialect dialectB = mock(OutboxDialect.class);

    @Test
    void picks_the_only_supporting_provider() throws SQLException {
        DialectAutoDetector detector =
                new DialectAutoDetector(
                        List.of(provider(true, 0, dialectA), provider(false, 0, dialectB)));
        assertThat(detector.detect(connection())).isSameAs(dialectA);
    }

    @Test
    void picks_higher_priority_when_multiple_providers_match() throws SQLException {
        DialectAutoDetector detector =
                new DialectAutoDetector(
                        List.of(provider(true, 0, dialectA), provider(true, 5, dialectB)));
        assertThat(detector.detect(connection())).isSameAs(dialectB);
    }

    @Test
    void preserves_first_match_when_priorities_are_equal() throws SQLException {
        DialectAutoDetector detector =
                new DialectAutoDetector(
                        List.of(provider(true, 1, dialectA), provider(true, 1, dialectB)));
        assertThat(detector.detect(connection())).isSameAs(dialectA);
    }

    @Test
    void skips_providers_whose_supports_throws_sql_exception() throws SQLException {
        OutboxDialectProvider throwing =
                new OutboxDialectProvider() {
                    @Override
                    public boolean supports(DatabaseMetaData metaData) throws SQLException {
                        throw new SQLException("metadata access denied");
                    }

                    @Override
                    public OutboxDialect create() {
                        throw new AssertionError("should not be created");
                    }
                };
        DialectAutoDetector detector =
                new DialectAutoDetector(List.of(throwing, provider(true, 0, dialectA)));
        assertThat(detector.detect(connection())).isSameAs(dialectA);
    }

    @Test
    void throws_configuration_exception_when_no_provider_matches() throws SQLException {
        DialectAutoDetector detector =
                new DialectAutoDetector(List.of(provider(false, 0, dialectA)));
        assertThatThrownBy(() -> detector.detect(connection()))
                .isInstanceOf(OutboxConfigurationException.class)
                .hasMessageContaining("FakeDB");
    }

    @Test
    void throws_configuration_exception_when_no_provider_is_registered() throws SQLException {
        DialectAutoDetector detector = new DialectAutoDetector(List.of());
        assertThatThrownBy(() -> detector.detect(connection()))
                .isInstanceOf(OutboxConfigurationException.class);
    }

    @Test
    void using_service_loader_returns_a_working_detector() {
        // Smoke test: the production factory must succeed and produce a non-null detector.
        // The actual ServiceLoader behavior (resolving PostgresDialectProvider) is exercised
        // by JdbcOutboxDialectAutoDetectionIT against a real PostgreSQL container.
        assertThat(DialectAutoDetector.usingServiceLoader()).isNotNull();
    }

    private static OutboxDialectProvider provider(
            boolean supports, int priority, OutboxDialect created) {
        return new OutboxDialectProvider() {
            @Override
            public boolean supports(DatabaseMetaData metaData) {
                return supports;
            }

            @Override
            public OutboxDialect create() {
                return created;
            }

            @Override
            public int priority() {
                return priority;
            }
        };
    }

    private static Connection connection() throws SQLException {
        Connection connection = mock(Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn("FakeDB");
        return connection;
    }
}
