package io.github.lobofoltran.outbox.jdbc;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Provides the JDBC {@link Connection} that should participate in the caller's transaction.
 *
 * <p>{@link JdbcOutbox} obtains a connection from this supplier on every {@link JdbcOutbox#publish
 * publish} call and does <em>not</em> close it. The caller owns the connection lifecycle.
 *
 * <p>Typical implementations:
 *
 * <ul>
 *   <li>In a Spring application: {@code () -> DataSourceUtils.getConnection(dataSource)}.
 *   <li>In plain JDBC: a closure capturing the current thread's transactional {@code Connection}.
 * </ul>
 */
@FunctionalInterface
public interface ConnectionSupplier {

    /**
     * Returns the {@link Connection} bound to the caller's current transaction.
     *
     * @throws SQLException if obtaining the connection fails.
     */
    Connection get() throws SQLException;
}
