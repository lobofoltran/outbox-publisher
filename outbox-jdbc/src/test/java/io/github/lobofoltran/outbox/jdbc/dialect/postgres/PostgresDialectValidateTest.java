/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox.jdbc.dialect.postgres;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.lobofoltran.outbox.OutboxDataException;
import io.github.lobofoltran.outbox.OutboxEvent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PostgresDialect#validate(OutboxEvent)}. These cover the byte-length checks
 * that used to live in {@code OutboxEvent} but are now a dialect responsibility — see ADR /
 * CHANGELOG note for the rationale (column-width is database-specific, and PG / Oracle / MySQL
 * disagree on character vs byte counting).
 */
class PostgresDialectValidateTest {

    private final PostgresDialect dialect = new PostgresDialect();

    private static OutboxEvent.Builder validBuilder() {
        return OutboxEvent.builder()
                .aggregateType("Order")
                .aggregateId("ord-1")
                .eventType("OrderPlaced")
                .contentType("application/json")
                .payload(new byte[] {1, 2, 3});
    }

    @Nested
    @DisplayName("ascii happy path")
    class Ascii {

        @Test
        void accepts_strings_at_the_byte_boundary() {
            String s128 = "a".repeat(128);
            String s64 = "b".repeat(64);
            OutboxEvent event =
                    validBuilder()
                            .aggregateType(s128)
                            .aggregateId(s128)
                            .eventType(s128)
                            .contentType(s64)
                            .destination(s128)
                            .build();
            assertThatCode(() -> dialect.validate(event)).doesNotThrowAnyException();
        }

        @Test
        void rejects_aggregateType_over_128_bytes() {
            OutboxEvent event = validBuilder().aggregateType("a".repeat(129)).build();
            assertThatThrownBy(() -> dialect.validate(event))
                    .isInstanceOf(OutboxDataException.class)
                    .hasMessageContaining("aggregateType")
                    .hasMessageContaining("128")
                    .hasMessageContaining("bytes");
        }

        @Test
        void rejects_aggregateId_over_128_bytes() {
            OutboxEvent event = validBuilder().aggregateId("a".repeat(129)).build();
            assertThatThrownBy(() -> dialect.validate(event))
                    .isInstanceOf(OutboxDataException.class)
                    .hasMessageContaining("aggregateId");
        }

        @Test
        void rejects_eventType_over_128_bytes() {
            OutboxEvent event = validBuilder().eventType("a".repeat(129)).build();
            assertThatThrownBy(() -> dialect.validate(event))
                    .isInstanceOf(OutboxDataException.class)
                    .hasMessageContaining("eventType");
        }

        @Test
        void rejects_contentType_over_64_bytes() {
            OutboxEvent event = validBuilder().contentType("a".repeat(65)).build();
            assertThatThrownBy(() -> dialect.validate(event))
                    .isInstanceOf(OutboxDataException.class)
                    .hasMessageContaining("contentType")
                    .hasMessageContaining("64");
        }

        @Test
        void rejects_destination_over_128_bytes() {
            OutboxEvent event = validBuilder().destination("a".repeat(129)).build();
            assertThatThrownBy(() -> dialect.validate(event))
                    .isInstanceOf(OutboxDataException.class)
                    .hasMessageContaining("destination");
        }

        @Test
        void allows_null_destination() {
            OutboxEvent event = validBuilder().destination(null).build();
            assertThatCode(() -> dialect.validate(event)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("multibyte (UTF-8) — checks bytes, not characters")
    class MultiByte {

        @Test
        void rejects_aggregateType_when_utf8_bytes_exceed_limit_even_if_char_count_does_not() {
            // "é" is 2 bytes in UTF-8. 65 × 2 = 130 bytes — over 128, but only 65 characters.
            // A String.length() check would have wrongly accepted this; the byte check rejects.
            String multibyte = "é".repeat(65);
            OutboxEvent event = validBuilder().aggregateType(multibyte).build();
            assertThatThrownBy(() -> dialect.validate(event))
                    .isInstanceOf(OutboxDataException.class)
                    .hasMessageContaining("aggregateType")
                    .hasMessageContaining("130");
        }

        @Test
        void rejects_contentType_when_utf8_bytes_exceed_limit() {
            // 33 × 2 = 66 bytes — over the 64-byte contentType limit.
            String multibyte = "é".repeat(33);
            OutboxEvent event = validBuilder().contentType(multibyte).build();
            assertThatThrownBy(() -> dialect.validate(event))
                    .isInstanceOf(OutboxDataException.class)
                    .hasMessageContaining("contentType")
                    .hasMessageContaining("64");
        }

        @Test
        void accepts_multibyte_string_under_byte_limit() {
            // 60 × 2 = 120 bytes — under the 128-byte limit.
            OutboxEvent event = validBuilder().aggregateType("é".repeat(60)).build();
            assertThatCode(() -> dialect.validate(event)).doesNotThrowAnyException();
        }
    }
}
