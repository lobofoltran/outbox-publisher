/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox;

import java.io.Serial;

/**
 * Signals that the event itself is malformed for the underlying store: a column value out of range,
 * a string that exceeds the declared length, an invalid JSON document for the {@code headers}
 * column, or a numeric overflow.
 *
 * <p><strong>What the caller should do:</strong> <em>abort</em>. Do not retry — the same payload
 * will fail the same way. Treat this as a programming error or a data-quality bug upstream and
 * surface it to the application's error reporting.
 *
 * <p>Dialects classify SQLState classes {@code 22} (data exception) and {@code 21} (cardinality
 * violation) as data failures.
 *
 * @since 0.1.0
 */
public final class OutboxDataException extends OutboxException {

    @Serial private static final long serialVersionUID = 1L;

    /**
     * Creates a new {@code OutboxDataException} with the given message.
     *
     * @param message human-readable description of the failure.
     * @since 0.1.0
     */
    public OutboxDataException(String message) {
        super(message);
    }

    /**
     * Creates a new {@code OutboxDataException} with the given message and cause.
     *
     * @param message human-readable description of the failure.
     * @param cause the underlying cause.
     * @since 0.1.0
     */
    public OutboxDataException(String message, Throwable cause) {
        super(message, cause);
    }
}
