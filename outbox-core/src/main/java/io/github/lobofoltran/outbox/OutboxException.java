package io.github.lobofoltran.outbox;

import java.io.Serial;

/**
 * Thrown when the outbox store rejects a write that the caller had no way to prevent — typically an
 * underlying SQL error, a connectivity failure, or a schema mismatch.
 *
 * <p>This is an unchecked exception by design: callers are not expected to recover from outbox
 * failures programmatically. A failure here usually means the surrounding transaction should abort.
 *
 * <p>This type is the root of a sealed hierarchy. Implementations (notably {@code outbox-jdbc})
 * translate driver-level errors into one of the four leaf subtypes so that callers and operators
 * can reason about the failure class without parsing SQLState codes:
 *
 * <ul>
 *   <li>{@link OutboxTransientException} — retry the surrounding transaction
 *   <li>{@link OutboxIntegrityException} — the row already exists; treat the publish as idempotent
 *       success or skip
 *   <li>{@link OutboxDataException} — the payload or column data is invalid; abort, do not retry
 *   <li>{@link OutboxConfigurationException} — schema or connectivity is wrong; fail fast at
 *       startup
 * </ul>
 *
 * <p>Code that previously caught {@code OutboxException} continues to work unchanged because every
 * subtype is still an {@code OutboxException}.
 */
public sealed class OutboxException extends RuntimeException
        permits OutboxTransientException,
                OutboxIntegrityException,
                OutboxDataException,
                OutboxConfigurationException {

    @Serial private static final long serialVersionUID = 1L;

    public OutboxException(String message) {
        super(message);
    }

    public OutboxException(String message, Throwable cause) {
        super(message, cause);
    }
}
