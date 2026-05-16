package io.github.lobofoltran.outbox;

import java.io.Serial;

/**
 * Signals an integrity-constraint violation: most commonly a duplicate primary key, meaning an
 * event with the same {@code id} has already been written to the outbox.
 *
 * <p><strong>What the caller should do:</strong> treat the publish as idempotent success and
 * <em>skip</em>. Do not retry — the row is already there, and a retry will hit the same constraint.
 * Surface the condition only if the application's correctness model treats duplicate IDs as a bug.
 *
 * <p>Dialects classify SQLState class {@code 23} (integrity constraint violation) — in particular
 * {@code 23505} (unique violation) on PostgreSQL — as integrity failures.
 */
public final class OutboxIntegrityException extends OutboxException {

    @Serial private static final long serialVersionUID = 1L;

    public OutboxIntegrityException(String message) {
        super(message);
    }

    public OutboxIntegrityException(String message, Throwable cause) {
        super(message, cause);
    }
}
