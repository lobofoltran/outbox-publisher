/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox;

import java.io.Serial;

/**
 * Signals that an {@link OutboxEvent} cannot be constructed because the caller supplied an invalid
 * field value: a {@code null} required component, a blank string where a non-blank value is
 * required, or a {@code null} key/value inside the {@code headers} map.
 *
 * <p><strong>What the caller should do:</strong> <em>abort</em>. This is a programming bug in the
 * caller — the same inputs will fail the same way on every retry. Surface the exception to the
 * application's error reporting and fix the construction site.
 *
 * <p>Unlike {@link OutboxDataException}, which is raised by the dialect after the value reached the
 * JDBC layer (typically because it overflows a column width or violates a SQLState 22/21 rule),
 * {@code OutboxValidationException} is raised upfront by {@link OutboxEvent#builder()} and the
 * record's compact constructor — before any SQL is issued. Both subtypes prescribe "abort, do not
 * retry"; they exist as separate leaves so callers can distinguish "the caller passed bad
 * arguments" from "the database rejected the row".
 *
 * <p>This leaf is part of the sealed {@link OutboxException} hierarchy so that a single {@code
 * catch (OutboxException e)} around the {@code builder()...build()} + {@code publish(event)}
 * sequence is sufficient to handle every failure originating in this library.
 *
 * @since 0.4.0
 */
public final class OutboxValidationException extends OutboxException {

    @Serial private static final long serialVersionUID = 1L;

    /**
     * Creates a new {@code OutboxValidationException} with the given message.
     *
     * @param message human-readable description of the failure.
     * @since 0.4.0
     */
    public OutboxValidationException(String message) {
        super(message);
    }

    /**
     * Creates a new {@code OutboxValidationException} with the given message and cause.
     *
     * @param message human-readable description of the failure.
     * @param cause the underlying cause.
     * @since 0.4.0
     */
    public OutboxValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
