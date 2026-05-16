/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox.jdbc;

import java.time.Clock;
import java.util.UUID;

/**
 * Produces the {@link UUID} written to the {@code id} column when the caller did not set one on the
 * {@link io.github.lobofoltran.outbox.OutboxEvent}.
 *
 * <p>The default implementation is UUIDv7 (RFC 9562) which is time-ordered and therefore cheap to
 * index. Custom implementations may supply UUIDv4 or domain-specific identifiers.
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface IdGenerator {

    /**
     * Generates a fresh identifier, optionally honoring {@code clock} for timestamp-based UUID
     * variants.
     *
     * @param clock the clock to use for timestamp-based variants.
     * @return a fresh identifier.
     * @since 0.1.0
     */
    UUID generate(Clock clock);
}
