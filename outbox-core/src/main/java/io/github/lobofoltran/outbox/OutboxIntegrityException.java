/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
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
 *
 * @since 0.2.0
 */
public final class OutboxIntegrityException extends OutboxException {

    @Serial private static final long serialVersionUID = 1L;

    /**
     * Creates a new {@code OutboxIntegrityException} with the given message.
     *
     * @param message human-readable description of the failure.
     * @since 0.2.0
     */
    public OutboxIntegrityException(String message) {
        super(message);
    }

    /**
     * Creates a new {@code OutboxIntegrityException} with the given message and cause.
     *
     * @param message human-readable description of the failure.
     * @param cause the underlying cause.
     * @since 0.2.0
     */
    public OutboxIntegrityException(String message, Throwable cause) {
        super(message, cause);
    }
}
