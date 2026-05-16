package io.github.lobofoltran.outbox;

import java.io.Serial;

/**
 * Signals a transient failure of the underlying store: connectivity loss, deadlock, serialization
 * conflict, lock timeout, or any condition the database itself classifies as retryable.
 *
 * <p><strong>What the caller should do:</strong> retry the surrounding business transaction. The
 * outbox row was not persisted; the next attempt will re-issue the INSERT. Use the application's
 * normal retry/back-off policy — the outbox library does not retry on the caller's behalf.
 *
 * <p>Dialects classify SQLState classes {@code 08} (connection exception) and {@code 40}
 * (transaction rollback / serialization failure / deadlock) as transient.
 */
public final class OutboxTransientException extends OutboxException {

    @Serial private static final long serialVersionUID = 1L;

    public OutboxTransientException(String message) {
        super(message);
    }

    public OutboxTransientException(String message, Throwable cause) {
        super(message, cause);
    }
}
