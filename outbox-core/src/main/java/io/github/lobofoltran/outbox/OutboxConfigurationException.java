package io.github.lobofoltran.outbox;

import java.io.Serial;

/**
 * Signals that the outbox cannot operate because the deployment is misconfigured: the {@code
 * outbox} table is missing, columns do not match the expected schema, the JDBC driver is not on the
 * classpath, or the configured {@code DataSource} cannot be opened at all.
 *
 * <p><strong>What the caller should do:</strong> <em>fail fast</em>, ideally at application
 * startup. Do not retry — the condition will not resolve itself without an operator action (apply
 * the migration, fix the connection string, deploy the right driver). Surface the exception so the
 * deployment is rejected.
 *
 * <p>Dialects classify SQLState classes {@code 42} (syntax error or access rule violation, e.g.
 * undefined table {@code 42P01} on PostgreSQL) and {@code 3D} (invalid catalog name) as
 * configuration failures.
 */
public final class OutboxConfigurationException extends OutboxException {

    @Serial private static final long serialVersionUID = 1L;

    public OutboxConfigurationException(String message) {
        super(message);
    }

    public OutboxConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
