/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class OutboxTest {

    @Test
    void publish_all_default_iterates_and_delegates_to_publish() {
        RecordingOutbox outbox = new RecordingOutbox();
        OutboxEvent first = event("a");
        OutboxEvent second = event("b");

        outbox.publishAll(List.of(first, second));

        assertThat(outbox.received).containsExactly(first, second);
    }

    @Test
    void publish_all_default_rejects_null_iterable() {
        RecordingOutbox outbox = new RecordingOutbox();
        assertThatThrownBy(() -> outbox.publishAll(null))
                .isInstanceOf(OutboxValidationException.class)
                .hasMessageContaining("events");
    }

    @Test
    void publish_all_default_propagates_publish_failure() {
        Outbox outbox =
                event -> {
                    throw new OutboxException("nope");
                };
        assertThatThrownBy(() -> outbox.publishAll(List.of(event("a"))))
                .isInstanceOf(OutboxException.class);
    }

    private static OutboxEvent event(String aggregateId) {
        return OutboxEvent.builder()
                .aggregateType("Order")
                .aggregateId(aggregateId)
                .eventType("OrderPlaced")
                .contentType("application/json")
                .payload(new byte[] {1})
                .occurredAt(Instant.parse("2026-03-10T08:30:00Z"))
                .build();
    }

    private static final class RecordingOutbox implements Outbox {
        private final List<OutboxEvent> received = new ArrayList<>();

        @Override
        public void publish(OutboxEvent event) {
            received.add(event);
        }
    }
}
