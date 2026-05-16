/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class OutboxEventTest {

    private static final String OK_AGG_TYPE = "Order";
    private static final String OK_AGG_ID = "ord-1";
    private static final String OK_EVENT_TYPE = "OrderPlaced";
    private static final String OK_CONTENT_TYPE = "application/json";
    private static final byte[] OK_PAYLOAD = new byte[] {1, 2, 3};

    private static OutboxEvent.Builder validBuilder() {
        return OutboxEvent.builder()
                .aggregateType(OK_AGG_TYPE)
                .aggregateId(OK_AGG_ID)
                .eventType(OK_EVENT_TYPE)
                .contentType(OK_CONTENT_TYPE)
                .payload(OK_PAYLOAD);
    }

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        void builds_with_all_fields_populated() {
            UUID id = UUID.randomUUID();
            Instant occurredAt = Instant.parse("2026-01-01T00:00:00Z");

            OutboxEvent event =
                    OutboxEvent.builder()
                            .id(id)
                            .aggregateType("Order")
                            .aggregateId("ord-1")
                            .eventType("OrderPlaced")
                            .contentType("application/json")
                            .payload(new byte[] {7, 8})
                            .header("k1", "v1")
                            .header("k2", "v2")
                            .destination("orders.events")
                            .occurredAt(occurredAt)
                            .build();

            assertThat(event.id()).isEqualTo(id);
            assertThat(event.aggregateType()).isEqualTo("Order");
            assertThat(event.aggregateId()).isEqualTo("ord-1");
            assertThat(event.eventType()).isEqualTo("OrderPlaced");
            assertThat(event.contentType()).isEqualTo("application/json");
            assertThat(event.payload()).containsExactly(7, 8);
            assertThat(event.headers())
                    .containsExactly(Map.entry("k1", "v1"), Map.entry("k2", "v2"));
            assertThat(event.destination()).isEqualTo("orders.events");
            assertThat(event.occurredAt()).isEqualTo(occurredAt);
        }

        @Test
        void allows_null_id() {
            OutboxEvent event = validBuilder().build();
            assertThat(event.id()).isNull();
        }

        @Test
        void allows_null_destination() {
            OutboxEvent event = validBuilder().destination(null).build();
            assertThat(event.destination()).isNull();
        }

        @Test
        void defaults_occurredAt_to_now() {
            Instant before = Instant.now();
            OutboxEvent event = validBuilder().build();
            Instant after = Instant.now();
            assertThat(event.occurredAt()).isBetween(before, after);
        }

        @Test
        void defaults_headers_to_empty_map() {
            OutboxEvent event = validBuilder().build();
            assertThat(event.headers()).isEmpty();
        }

        @Test
        void accepts_arbitrarily_long_strings_at_the_record_level() {
            // The record itself does not enforce width — that is a dialect concern. See
            // PostgresDialectValidateTest for the publish-time check.
            String wide = "a".repeat(1024);
            OutboxEvent event =
                    OutboxEvent.builder()
                            .aggregateType(wide)
                            .aggregateId(wide)
                            .eventType(wide)
                            .contentType(wide)
                            .destination(wide)
                            .payload(OK_PAYLOAD)
                            .build();
            assertThat(event.aggregateType()).hasSize(1024);
            assertThat(event.contentType()).hasSize(1024);
            assertThat(event.destination()).hasSize(1024);
        }
    }

    @Nested
    @DisplayName("null validation")
    class NullValidation {

        @Test
        void rejects_null_aggregateType() {
            assertThatThrownBy(() -> validBuilder().aggregateType(null).build())
                    .isInstanceOf(OutboxValidationException.class)
                    .hasMessageContaining("aggregateType");
        }

        @Test
        void rejects_null_aggregateId() {
            assertThatThrownBy(() -> validBuilder().aggregateId(null).build())
                    .isInstanceOf(OutboxValidationException.class)
                    .hasMessageContaining("aggregateId");
        }

        @Test
        void rejects_null_eventType() {
            assertThatThrownBy(() -> validBuilder().eventType(null).build())
                    .isInstanceOf(OutboxValidationException.class)
                    .hasMessageContaining("eventType");
        }

        @Test
        void rejects_null_contentType() {
            assertThatThrownBy(() -> validBuilder().contentType(null).build())
                    .isInstanceOf(OutboxValidationException.class)
                    .hasMessageContaining("contentType");
        }

        @Test
        void rejects_null_payload() {
            assertThatThrownBy(() -> validBuilder().payload(null).build())
                    .isInstanceOf(OutboxValidationException.class)
                    .hasMessageContaining("payload");
        }

        @Test
        void rejects_null_headers_map_via_record_constructor() {
            assertThatThrownBy(
                            () ->
                                    new OutboxEvent(
                                            null,
                                            OK_AGG_TYPE,
                                            OK_AGG_ID,
                                            OK_EVENT_TYPE,
                                            OK_CONTENT_TYPE,
                                            OK_PAYLOAD,
                                            null,
                                            null,
                                            Instant.now()))
                    .isInstanceOf(OutboxValidationException.class)
                    .hasMessageContaining("headers");
        }

        @Test
        void rejects_null_occurredAt_via_record_constructor() {
            assertThatThrownBy(
                            () ->
                                    new OutboxEvent(
                                            null,
                                            OK_AGG_TYPE,
                                            OK_AGG_ID,
                                            OK_EVENT_TYPE,
                                            OK_CONTENT_TYPE,
                                            OK_PAYLOAD,
                                            Map.of(),
                                            null,
                                            null))
                    .isInstanceOf(OutboxValidationException.class)
                    .hasMessageContaining("occurredAt");
        }

        @Test
        void rejects_null_key_in_header() {
            assertThatThrownBy(() -> validBuilder().header(null, "v"))
                    .isInstanceOf(OutboxValidationException.class);
        }

        @Test
        void rejects_null_value_in_header() {
            assertThatThrownBy(() -> validBuilder().header("k", null))
                    .isInstanceOf(OutboxValidationException.class);
        }

        @Test
        void rejects_null_map_in_headers() {
            assertThatThrownBy(() -> validBuilder().headers(null))
                    .isInstanceOf(OutboxValidationException.class);
        }

        @Test
        void rejects_headers_map_with_null_key_via_record_constructor() {
            Map<String, String> bad = new HashMap<>();
            bad.put(null, "v");
            assertThatThrownBy(
                            () ->
                                    new OutboxEvent(
                                            null,
                                            OK_AGG_TYPE,
                                            OK_AGG_ID,
                                            OK_EVENT_TYPE,
                                            OK_CONTENT_TYPE,
                                            OK_PAYLOAD,
                                            bad,
                                            null,
                                            Instant.now()))
                    .isInstanceOf(OutboxValidationException.class)
                    .hasMessageContaining("header keys");
        }

        @Test
        void rejects_headers_map_with_null_value_via_record_constructor() {
            Map<String, String> bad = new HashMap<>();
            bad.put("k", null);
            assertThatThrownBy(
                            () ->
                                    new OutboxEvent(
                                            null,
                                            OK_AGG_TYPE,
                                            OK_AGG_ID,
                                            OK_EVENT_TYPE,
                                            OK_CONTENT_TYPE,
                                            OK_PAYLOAD,
                                            bad,
                                            null,
                                            Instant.now()))
                    .isInstanceOf(OutboxValidationException.class)
                    .hasMessageContaining("header values");
        }

        @Test
        void null_validation_failures_are_subtypes_of_OutboxException() {
            // Sealed-hierarchy contract: a single catch (OutboxException) covers construction.
            assertThatThrownBy(() -> validBuilder().aggregateType(null).build())
                    .isInstanceOf(OutboxException.class);
        }
    }

    @Nested
    @DisplayName("blank validation")
    class BlankValidation {

        @Test
        void rejects_blank_aggregateType() {
            assertThatThrownBy(() -> validBuilder().aggregateType("   ").build())
                    .isInstanceOf(OutboxValidationException.class)
                    .hasMessageContaining("aggregateType");
        }

        @Test
        void rejects_blank_aggregateId() {
            assertThatThrownBy(() -> validBuilder().aggregateId("").build())
                    .isInstanceOf(OutboxValidationException.class)
                    .hasMessageContaining("aggregateId");
        }

        @Test
        void rejects_blank_eventType() {
            assertThatThrownBy(() -> validBuilder().eventType("").build())
                    .isInstanceOf(OutboxValidationException.class)
                    .hasMessageContaining("eventType");
        }

        @Test
        void rejects_blank_contentType() {
            assertThatThrownBy(() -> validBuilder().contentType("\t\n").build())
                    .isInstanceOf(OutboxValidationException.class)
                    .hasMessageContaining("contentType");
        }

        @Test
        void blank_validation_failures_are_subtypes_of_OutboxException() {
            assertThatThrownBy(() -> validBuilder().aggregateType("").build())
                    .isInstanceOf(OutboxException.class);
        }
    }

    @Nested
    @DisplayName("defensive copies")
    class DefensiveCopies {

        @Test
        void payload_mutations_to_input_do_not_leak_into_event() {
            byte[] original = {1, 2, 3};
            OutboxEvent event = validBuilder().payload(original).build();
            original[0] = 99;
            assertThat(event.payload()).containsExactly(1, 2, 3);
        }

        @Test
        void payload_mutations_to_accessor_result_do_not_affect_event() {
            OutboxEvent event = validBuilder().payload(new byte[] {1, 2, 3}).build();
            byte[] copy = event.payload();
            copy[0] = 99;
            assertThat(event.payload()).containsExactly(1, 2, 3);
        }

        @Test
        void payload_accessor_returns_fresh_copy_each_call() {
            OutboxEvent event = validBuilder().payload(new byte[] {1}).build();
            assertThat(event.payload()).isNotSameAs(event.payload());
        }

        @Test
        void header_mutations_to_input_map_do_not_leak_into_event() {
            Map<String, String> mutable = new HashMap<>();
            mutable.put("a", "1");
            OutboxEvent event = validBuilder().headers(mutable).build();
            mutable.put("b", "2");
            assertThat(event.headers()).containsExactly(Map.entry("a", "1"));
        }

        @Test
        void headers_accessor_returns_unmodifiable_map() {
            OutboxEvent event =
                    validBuilder().headers(new LinkedHashMap<>(Map.of("a", "1"))).build();
            assertThatThrownBy(() -> event.headers().put("b", "2"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void builder_header_overrides_previous_value_for_same_key() {
            OutboxEvent event = validBuilder().header("k", "v1").header("k", "v2").build();
            assertThat(event.headers()).containsEntry("k", "v2");
        }

        @Test
        void builder_headers_replaces_previously_accumulated_entries() {
            OutboxEvent event = validBuilder().header("a", "1").headers(Map.of("b", "2")).build();
            assertThat(event.headers()).containsExactly(Map.entry("b", "2"));
        }
    }

    @Nested
    @DisplayName("payloadSize")
    class PayloadSize {

        @Test
        void returns_underlying_payload_length() {
            OutboxEvent event = validBuilder().payload(new byte[] {1, 2, 3, 4, 5}).build();
            assertThat(event.payloadSize()).isEqualTo(5);
        }

        @Test
        void returns_zero_for_empty_payload() {
            OutboxEvent event = validBuilder().payload(new byte[0]).build();
            assertThat(event.payloadSize()).isZero();
        }

        @Test
        void does_not_clone_payload() {
            // A 1 MiB payload makes each defensive clone non-trivial (well into microseconds).
            // 1,000,000 calls to payloadSize() must therefore be effectively instantaneous —
            // any implementation that cloned the array would take on the order of seconds,
            // not under the 100ms threshold below.
            byte[] big = new byte[1 << 20];
            OutboxEvent event = validBuilder().payload(big).build();

            long start = System.nanoTime();
            int total = 0;
            for (int i = 0; i < 1_000_000; i++) {
                total += event.payloadSize();
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

            assertThat(total).isEqualTo(1_000_000 * (1 << 20));
            assertThat(elapsedMs)
                    .as("1M payloadSize() calls completed in %d ms", elapsedMs)
                    .isLessThan(100L);
        }
    }

    @Nested
    @DisplayName("equality")
    class Equality {

        @Test
        void identical_field_values_are_equal_for_non_array_fields() {
            UUID id = UUID.randomUUID();
            Instant t = Instant.parse("2026-01-01T00:00:00Z");
            byte[] shared = {1, 2, 3};
            // Builder calls clone() on payload internally — to assert canonical equality
            // we go through the record constructor directly with the same array reference,
            // then verify the record's defensive copy still preserves ref-equality semantics.
            OutboxEvent a =
                    OutboxEvent.builder()
                            .id(id)
                            .aggregateType("T")
                            .aggregateId("I")
                            .eventType("E")
                            .contentType("C")
                            .payload(shared)
                            .occurredAt(t)
                            .build();
            OutboxEvent b =
                    OutboxEvent.builder()
                            .id(id)
                            .aggregateType("T")
                            .aggregateId("I")
                            .eventType("E")
                            .contentType("C")
                            .payload(shared)
                            .occurredAt(t)
                            .build();
            // Records use Objects.equals per component; byte[] compares by reference,
            // and the defensive copy means each event holds its own array instance.
            assertThat(a).isNotEqualTo(b);
            assertThat(a.id()).isEqualTo(b.id());
            assertThat(a.aggregateType()).isEqualTo(b.aggregateType());
        }

        @Test
        void toString_does_not_throw() {
            assertThat(validBuilder().build().toString()).contains("OutboxEvent");
        }
    }
}
