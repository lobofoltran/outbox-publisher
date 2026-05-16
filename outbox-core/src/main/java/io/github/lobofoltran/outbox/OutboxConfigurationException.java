/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
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
 *
 * @since 0.2.0
 */
public final class OutboxConfigurationException extends OutboxException {

    @Serial private static final long serialVersionUID = 1L;

    /**
     * Creates a new {@code OutboxConfigurationException} with the given message.
     *
     * @param message human-readable description of the failure.
     * @since 0.2.0
     */
    public OutboxConfigurationException(String message) {
        super(message);
    }

    /**
     * Creates a new {@code OutboxConfigurationException} with the given message and cause.
     *
     * @param message human-readable description of the failure.
     * @param cause the underlying cause.
     * @since 0.2.0
     */
    public OutboxConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
