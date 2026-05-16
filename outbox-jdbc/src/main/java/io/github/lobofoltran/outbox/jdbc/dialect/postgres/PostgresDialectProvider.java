/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox.jdbc.dialect.postgres;

import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Locale;

import io.github.lobofoltran.outbox.jdbc.spi.OutboxDialect;
import io.github.lobofoltran.outbox.jdbc.spi.OutboxDialectProvider;

/**
 * Auto-discovered provider for {@link PostgresDialect}. Matches any database whose product name
 * (case-insensitive) contains {@code postgres}, which covers vanilla PostgreSQL, AWS RDS for
 * PostgreSQL, Aurora PostgreSQL, Yugabyte (reports as {@code "PostgreSQL"}) and similar
 * wire-compatible derivatives. Priority is {@code 0} so that any third-party provider can override
 * us by returning a higher value.
 */
public final class PostgresDialectProvider implements OutboxDialectProvider {

    @Override
    public boolean supports(DatabaseMetaData metaData) throws SQLException {
        String product = metaData.getDatabaseProductName();
        return product != null && product.toLowerCase(Locale.ROOT).contains("postgres");
    }

    @Override
    public OutboxDialect create() {
        return new PostgresDialect();
    }
}
