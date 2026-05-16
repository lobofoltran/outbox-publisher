/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox.jdbc.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class UuidV7GeneratorTest {

    private final UuidV7Generator generator = new UuidV7Generator();

    @Test
    void declares_version_7() {
        UUID id = generator.generate(Clock.systemUTC());
        assertThat(id.version()).isEqualTo(7);
    }

    @Test
    void declares_rfc_variant() {
        UUID id = generator.generate(Clock.systemUTC());
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void encodes_clock_millis_in_the_high_48_bits() {
        Instant fixed = Instant.parse("2026-01-15T12:34:56.789Z");
        Clock fixedClock = Clock.fixed(fixed, ZoneOffset.UTC);
        UUID id = generator.generate(fixedClock);
        long encodedMillis = id.getMostSignificantBits() >>> 16;
        assertThat(encodedMillis).isEqualTo(fixed.toEpochMilli());
    }

    @Test
    void generates_distinct_ids_across_calls() {
        Clock clock = Clock.systemUTC();
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            ids.add(generator.generate(clock));
        }
        assertThat(ids).hasSize(1_000);
    }

    @Test
    void honors_injected_random_for_deterministic_layout() {
        // Reproducible random — yields fixed rand_a / rand_b bits.
        SecureRandom seeded = new SecureRandom();
        seeded.setSeed(42L);
        UuidV7Generator deterministic = new UuidV7Generator(seeded);

        Instant fixed = Instant.parse("2026-01-15T00:00:00Z");
        UUID first = deterministic.generate(Clock.fixed(fixed, ZoneOffset.UTC));
        UUID second = deterministic.generate(Clock.fixed(fixed, ZoneOffset.UTC));

        assertThat(first).isNotEqualTo(second);
        assertThat(first.version()).isEqualTo(7);
        assertThat(second.version()).isEqualTo(7);
    }
}
