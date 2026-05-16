package io.github.lobofoltran.outbox.jdbc.internal;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

import io.github.lobofoltran.outbox.jdbc.IdGenerator;

/**
 * RFC 9562 UUIDv7 generator. Encodes a 48-bit Unix-epoch milliseconds prefix followed by 74 random
 * bits, which makes the resulting identifiers time-ordered and B-tree-friendly.
 *
 * <p>Layout reminder (big-endian):
 *
 * <pre>{@code
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                          unix_ts_ms                           |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |          unix_ts_ms          |  ver  |        rand_a          |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |var|                        rand_b                             |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                            rand_b                             |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * }</pre>
 *
 * Instances are thread-safe.
 */
public final class UuidV7Generator implements IdGenerator {

    private static final long VERSION_7_BITS = 0x7000L;
    private static final long VARIANT_BITS = 0x8000_0000_0000_0000L;
    private static final long UNIX_TS_MS_MASK = 0x0000_FFFF_FFFF_FFFFL;
    private static final long RAND_A_MASK = 0x0000_0000_0000_0FFFL;
    private static final long RAND_B_MASK = 0x3FFF_FFFF_FFFF_FFFFL;

    private final SecureRandom random;

    public UuidV7Generator() {
        this(new SecureRandom());
    }

    UuidV7Generator(SecureRandom random) {
        this.random = random;
    }

    @Override
    public UUID generate(Clock clock) {
        long timestamp = clock.millis() & UNIX_TS_MS_MASK;
        long randomMsb = random.nextLong() & RAND_A_MASK;
        long randomLsb = random.nextLong() & RAND_B_MASK;

        long mostSignificant = (timestamp << 16) | VERSION_7_BITS | randomMsb;
        long leastSignificant = VARIANT_BITS | randomLsb;

        return new UUID(mostSignificant, leastSignificant);
    }
}
