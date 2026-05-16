/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox.jdbc.dialect.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.DatabaseMetaData;
import java.sql.SQLException;

import io.github.lobofoltran.outbox.jdbc.spi.OutboxDialect;

import org.junit.jupiter.api.Test;

class PostgresDialectProviderTest {

    private final PostgresDialectProvider provider = new PostgresDialectProvider();

    @Test
    void supports_returns_true_for_postgresql_product_name() throws SQLException {
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(metaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        assertThat(provider.supports(metaData)).isTrue();
    }

    @Test
    void supports_is_case_insensitive() throws SQLException {
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(metaData.getDatabaseProductName()).thenReturn("AURORA POSTGRES");
        assertThat(provider.supports(metaData)).isTrue();
    }

    @Test
    void supports_returns_false_for_other_databases() throws SQLException {
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(metaData.getDatabaseProductName()).thenReturn("MySQL");
        assertThat(provider.supports(metaData)).isFalse();
    }

    @Test
    void supports_returns_false_when_product_name_is_null() throws SQLException {
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(metaData.getDatabaseProductName()).thenReturn(null);
        assertThat(provider.supports(metaData)).isFalse();
    }

    @Test
    void create_returns_a_postgres_dialect_instance() {
        OutboxDialect dialect = provider.create();
        assertThat(dialect).isInstanceOf(PostgresDialect.class);
    }

    @Test
    void priority_is_zero_so_third_party_providers_can_override() {
        assertThat(provider.priority()).isZero();
    }
}
