package io.github.lobofoltran.outbox.jdbc;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Provides the JDBC {@link Connection} that should participate in the caller's transaction.
 *
 * <h2>Contract</h2>
 *
 * <p>{@link JdbcOutbox} obtains a connection from this supplier on every {@link JdbcOutbox#publish
 * publish} and {@link JdbcOutbox#publishAll publishAll} call. The supplier MUST satisfy the
 * following:
 *
 * <ul>
 *   <li><b>Same transaction as the caller's business writes.</b> The returned {@code Connection}
 *       must be the one bound to the surrounding transaction so that the outbox INSERT commits or
 *       rolls back atomically with those writes. In Spring this is what {@code
 *       DataSourceUtils.getConnection(dataSource)} returns; in plain JDBC this is the {@code
 *       Connection} the caller is currently holding open.
 *   <li><b>Manual transaction mode.</b> The returned connection MUST have {@code autoCommit ==
 *       false}. {@code JdbcOutbox} asserts this on every publish and throws {@link
 *       io.github.lobofoltran.outbox.OutboxConfigurationException} otherwise. An autocommit
 *       connection silently breaks the atomicity guarantee — the outbox row would be visible to the
 *       relay before the business row, defeating the pattern.
 *   <li><b>Open and usable.</b> The supplier returns a non-{@code null}, open connection. If
 *       obtaining the connection fails (pool exhaustion, network error, …), the supplier throws
 *       {@link SQLException} and {@code JdbcOutbox} translates it.
 *   <li><b>Lifecycle owned by the caller.</b> {@code JdbcOutbox} never calls {@code close()},
 *       {@code commit()}, or {@code rollback()} on the returned connection. The Spring transaction
 *       manager (or the caller's own try-with-resources) does that.
 * </ul>
 *
 * <h2>Examples</h2>
 *
 * <p><b>Spring (recommended).</b> Inside an {@code @Transactional} method, the autoconfiguration in
 * {@code outbox-spring} sets the supplier to:
 *
 * <pre>{@code
 * connectionSupplier(() -> DataSourceUtils.getConnection(dataSource))
 * }</pre>
 *
 * <p><b>Plain JDBC.</b> The caller manages the connection per unit of work and pins it to the
 * supplier for the duration:
 *
 * <pre>{@code
 * try (Connection connection = dataSource.getConnection()) {
 *     connection.setAutoCommit(false);
 *     Outbox outbox =
 *             JdbcOutbox.builder().connectionSupplier(() -> connection).build();
 *     try {
 *         persistBusinessRow(connection);
 *         outbox.publish(event);
 *         connection.commit();
 *     } catch (RuntimeException ex) {
 *         connection.rollback();
 *         throw ex;
 *     }
 * }
 * }</pre>
 *
 * <h2>Anti-patterns</h2>
 *
 * <ul>
 *   <li>DO NOT call {@code dataSource.getConnection()} from the supplier directly when running
 *       under a Spring transaction — the returned connection will be a fresh one outside the active
 *       transaction. Use {@code DataSourceUtils.getConnection(dataSource)} instead.
 *   <li>DO NOT enable autocommit. {@code JdbcOutbox} fails fast if it sees one.
 *   <li>DO NOT close the returned connection inside the supplier (e.g. by wrapping it in a
 *       try-with-resources). The caller still needs the connection to commit the surrounding
 *       transaction.
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
