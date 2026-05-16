package io.github.lobofoltran.outbox;

import java.io.Serial;

/**
 * Thrown when the outbox store rejects a write that the caller had no way to prevent — typically an
 * underlying SQL error, a connectivity failure, or a schema mismatch.
 *
 * <p>This is an unchecked exception by design: callers are not expected to recover from outbox
 * failures programmatically. A failure here usually means the surrounding transaction should abort.
 */
public class OutboxException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    public OutboxException(String message) {
        super(message);
    }

    public OutboxException(String message, Throwable cause) {
        super(message, cause);
    }
}
