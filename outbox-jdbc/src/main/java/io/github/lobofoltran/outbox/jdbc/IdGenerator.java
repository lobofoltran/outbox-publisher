package io.github.lobofoltran.outbox.jdbc;

import java.time.Clock;
import java.util.UUID;

/**
 * Produces the {@link UUID} written to the {@code id} column when the caller did not set one on the
 * {@link io.github.lobofoltran.outbox.OutboxEvent}.
 *
 * <p>The default implementation is UUIDv7 (RFC 9562) which is time-ordered and therefore cheap to
 * index. Custom implementations may supply UUIDv4 or domain-specific identifiers.
 */
@FunctionalInterface
public interface IdGenerator {

    /**
     * Generates a fresh identifier, optionally honoring {@code clock} for timestamp-based UUID
     * variants.
     */
    UUID generate(Clock clock);
}
