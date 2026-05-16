/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verifies that {@link OutboxEvent.Builder} fails fast on null arguments for required fields — at
 * the setter call itself, not lazily at {@link OutboxEvent.Builder#build()}. Stacktraces pointing
 * at the offending setter are part of the developer-experience contract.
 */
class OutboxEventBuilderTest {

    private static Stream<Arguments> nonNullableSetters() {
        return Stream.of(
                Arguments.of(
                        "aggregateType",
                        (Consumer<OutboxEvent.Builder>) b -> b.aggregateType(null)),
                Arguments.of(
                        "aggregateId", (Consumer<OutboxEvent.Builder>) b -> b.aggregateId(null)),
                Arguments.of("eventType", (Consumer<OutboxEvent.Builder>) b -> b.eventType(null)),
                Arguments.of(
                        "contentType", (Consumer<OutboxEvent.Builder>) b -> b.contentType(null)),
                Arguments.of("payload", (Consumer<OutboxEvent.Builder>) b -> b.payload(null)),
                Arguments.of("headers", (Consumer<OutboxEvent.Builder>) b -> b.headers(null)));
    }

    @ParameterizedTest(name = "{0}(null) throws OutboxValidationException eagerly")
    @MethodSource("nonNullableSetters")
    @DisplayName("non-nullable setters reject null at the call site, not at build()")
    void non_nullable_setters_reject_null_eagerly(
            String fieldName, Consumer<OutboxEvent.Builder> setterCall) {
        OutboxEvent.Builder builder = OutboxEvent.builder();
        assertThatThrownBy(() -> setterCall.accept(builder))
                .isInstanceOf(OutboxValidationException.class)
                .hasMessageContaining(fieldName);
    }

    @Test
    @DisplayName("id(null) is tolerated — id is genuinely optional, resolved at build time")
    void id_tolerates_null() {
        OutboxEvent.Builder builder = OutboxEvent.builder();
        assertThat(builder.id(null)).isSameAs(builder);
    }

    @Test
    @DisplayName("destination(null) is tolerated — destination is optional")
    void destination_tolerates_null() {
        OutboxEvent.Builder builder = OutboxEvent.builder();
        assertThat(builder.destination(null)).isSameAs(builder);
    }

    @Test
    @DisplayName("occurredAt(null) is tolerated — defaults to Instant.now() at build time")
    void occurredAt_tolerates_null() {
        OutboxEvent.Builder builder = OutboxEvent.builder();
        assertThat(builder.occurredAt(null)).isSameAs(builder);
    }
}
