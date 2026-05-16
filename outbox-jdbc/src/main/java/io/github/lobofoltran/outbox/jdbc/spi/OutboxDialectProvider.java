package io.github.lobofoltran.outbox.jdbc.spi;

import java.sql.DatabaseMetaData;
import java.sql.SQLException;

/**
 * Service-loaded factory for {@link OutboxDialect}. Implementations are registered via {@code
 * META-INF/services/io.github.lobofoltran.outbox.jdbc.spi.OutboxDialectProvider} (or via {@code
 * provides ... with ...} in {@code module-info.java}).
 *
 * <p>{@code JdbcOutbox} runs auto-detection on its first publish: it opens a connection, inspects
 * {@link DatabaseMetaData}, asks every registered provider whether it {@link #supports supports}
 * the database, and picks the matching provider with the highest {@link #priority priority}.
 * Built-in providers use priority {@code 0}; third-party providers may use larger values to take
 * precedence.
 */
public interface OutboxDialectProvider {

    /**
     * Returns {@code true} if this provider's dialect can handle the database described by {@code
     * metaData}.
     *
     * @throws SQLException if metadata access fails. Auto-detection treats any thrown {@code
     *     SQLException} as "this provider does not match" and tries the next one.
     */
    boolean supports(DatabaseMetaData metaData) throws SQLException;

    /** Creates a fresh dialect instance. */
    OutboxDialect create();

    /**
     * Priority used to break ties when multiple providers report {@link #supports supports} for the
     * same database. Higher wins. Built-in providers return {@code 0}.
     */
    default int priority() {
        return 0;
    }
}
