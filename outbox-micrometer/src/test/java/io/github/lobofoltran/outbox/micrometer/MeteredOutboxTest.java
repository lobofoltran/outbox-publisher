/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox.micrometer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import io.github.lobofoltran.outbox.Outbox;
import io.github.lobofoltran.outbox.OutboxEvent;
import io.github.lobofoltran.outbox.OutboxException;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MeteredOutboxTest {

    private MeterRegistry registry;
    private RecordingOutbox delegate;
    private MeteredOutbox metered;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        delegate = new RecordingOutbox();
        metered = new MeteredOutbox(delegate, registry);
    }

    @Test
    void delegates_publish_call_to_wrapped_outbox() {
        OutboxEvent event = event("Order", "OrderPlaced", new byte[] {1, 2, 3});
        metered.publish(event);
        assertThat(delegate.captured).isEqualTo(event);
    }

    @Test
    void records_success_timer_with_aggregate_and_event_tags() {
        metered.publish(event("Order", "OrderPlaced", new byte[] {1}));
        Timer timer = findTimer("Order", "OrderPlaced", "success");
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void records_failure_timer_and_rethrows_when_delegate_throws() {
        OutboxException cause = new OutboxException("nope");
        delegate.failWith = cause;

        assertThatThrownBy(() -> metered.publish(event("Order", "OrderPlaced", new byte[] {1})))
                .isSameAs(cause);

        Timer timer = findTimer("Order", "OrderPlaced", "failure");
        assertThat(timer.count()).isEqualTo(1);
        Timer successTimer =
                registry.find(MeteredOutbox.PUBLISH_TIMER)
                        .tags(
                                Tags.of(
                                        "aggregate_type",
                                        "Order",
                                        "event_type",
                                        "OrderPlaced",
                                        "result",
                                        "success"))
                        .timer();
        assertThat(successTimer).isNull();
    }

    @Test
    void records_payload_bytes_distribution() {
        byte[] payload = new byte[] {1, 2, 3, 4, 5};
        metered.publish(event("Order", "OrderPlaced", payload));

        DistributionSummary summary =
                registry.find(MeteredOutbox.PAYLOAD_SUMMARY)
                        .tags(Tags.of("aggregate_type", "Order", "event_type", "OrderPlaced"))
                        .summary();
        assertThat(summary).isNotNull();
        assertThat(summary.count()).isEqualTo(1);
        assertThat(summary.totalAmount()).isEqualTo(payload.length);
        assertThat(summary.getId().getBaseUnit()).isEqualTo("bytes");
    }

    @Test
    void records_payload_bytes_even_when_delegate_fails() {
        delegate.failWith = new OutboxException("nope");
        byte[] payload = new byte[] {1, 2, 3};
        assertThatThrownBy(() -> metered.publish(event("Order", "OrderPlaced", payload)))
                .isInstanceOf(OutboxException.class);

        DistributionSummary summary =
                registry.find(MeteredOutbox.PAYLOAD_SUMMARY)
                        .tags(Tags.of("aggregate_type", "Order", "event_type", "OrderPlaced"))
                        .summary();
        assertThat(summary.totalAmount()).isEqualTo(payload.length);
    }

    @Test
    void tags_with_event_aggregate_and_event_type_distinctly() {
        metered.publish(event("Order", "OrderPlaced", new byte[] {1}));
        metered.publish(event("Customer", "CustomerCreated", new byte[] {1}));

        assertThat(findTimer("Order", "OrderPlaced", "success").count()).isEqualTo(1);
        assertThat(findTimer("Customer", "CustomerCreated", "success").count()).isEqualTo(1);
    }

    @Test
    void does_not_tag_aggregate_id_or_destination() {
        OutboxEvent event =
                OutboxEvent.builder()
                        .aggregateType("Order")
                        .aggregateId("ord-secret-42")
                        .eventType("OrderPlaced")
                        .contentType("application/json")
                        .payload(new byte[] {1})
                        .destination("orders.tenant-XYZ")
                        .occurredAt(Instant.now())
                        .build();
        metered.publish(event);

        Timer timer = findTimer("Order", "OrderPlaced", "success");
        assertThat(timer.getId().getTags())
                .extracting("key")
                .doesNotContain("aggregate_id", "destination");
    }

    @Test
    void publish_does_not_clone_payload_on_hot_path() {
        // OutboxEvent is a final record so we cannot spy on payload(). Instead, assert the
        // optimization indirectly: with a 4 MiB payload, 1,000 publishes complete well under
        // the lax threshold below. An implementation that called payload() on the hot path
        // would clone ~4 GiB total, taking seconds, not <2 s.
        byte[] big = new byte[4 << 20];
        OutboxEvent event = event("Order", "OrderPlaced", big);

        long start = System.nanoTime();
        for (int i = 0; i < 1_000; i++) {
            metered.publish(event);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        DistributionSummary summary =
                registry.find(MeteredOutbox.PAYLOAD_SUMMARY)
                        .tags(Tags.of("aggregate_type", "Order", "event_type", "OrderPlaced"))
                        .summary();
        assertThat(summary.count()).isEqualTo(1_000L);
        assertThat(elapsedMs)
                .as("1,000 publishes of a 4 MiB payload completed in %d ms", elapsedMs)
                .isLessThan(2_000L);
    }

    @Test
    void rejects_null_delegate() {
        assertThatNullPointerException()
                .isThrownBy(() -> new MeteredOutbox(null, registry))
                .withMessageContaining("delegate");
    }

    @Test
    void rejects_null_registry() {
        assertThatNullPointerException()
                .isThrownBy(() -> new MeteredOutbox(delegate, null))
                .withMessageContaining("registry");
    }

    @Test
    void rejects_null_event() {
        assertThatNullPointerException()
                .isThrownBy(() -> metered.publish(null))
                .withMessageContaining("event");
    }

    @Test
    void publish_all_delegates_and_records_batch_metrics() {
        List<OutboxEvent> events =
                List.of(
                        event("Order", "OrderPlaced", new byte[] {1, 2}),
                        event("Order", "OrderPlaced", new byte[] {3, 4, 5}));

        metered.publishAll(events);

        assertThat(delegate.batchCaptured).containsExactlyElementsOf(events);

        Timer batchTimer =
                registry.find(MeteredOutbox.BATCH_TIMER).tags(Tags.of("result", "success")).timer();
        assertThat(batchTimer).isNotNull();
        assertThat(batchTimer.count()).isEqualTo(1);

        DistributionSummary sizeSummary = registry.find(MeteredOutbox.BATCH_SIZE_SUMMARY).summary();
        assertThat(sizeSummary).isNotNull();
        assertThat(sizeSummary.count()).isEqualTo(1);
        assertThat(sizeSummary.totalAmount()).isEqualTo(events.size());

        DistributionSummary payload =
                registry.find(MeteredOutbox.PAYLOAD_SUMMARY)
                        .tags(Tags.of("aggregate_type", "Order", "event_type", "OrderPlaced"))
                        .summary();
        assertThat(payload.count()).isEqualTo(2);
        assertThat(payload.totalAmount()).isEqualTo(2 + 3);
    }

    @Test
    void publish_all_records_failure_timer_when_delegate_throws() {
        OutboxException cause = new OutboxException("nope");
        delegate.failWith = cause;

        assertThatThrownBy(
                        () ->
                                metered.publishAll(
                                        List.of(event("Order", "OrderPlaced", new byte[] {1}))))
                .isSameAs(cause);

        Timer batchTimer =
                registry.find(MeteredOutbox.BATCH_TIMER).tags(Tags.of("result", "failure")).timer();
        assertThat(batchTimer).isNotNull();
        assertThat(batchTimer.count()).isEqualTo(1);
    }

    @Test
    void publish_all_rejects_null_iterable() {
        assertThatNullPointerException()
                .isThrownBy(() -> metered.publishAll(null))
                .withMessageContaining("events");
    }

    @Test
    void publish_all_rejects_null_element() {
        List<OutboxEvent> events = new ArrayList<>();
        events.add(event("Order", "OrderPlaced", new byte[] {1}));
        events.add(null);
        assertThatNullPointerException().isThrownBy(() -> metered.publishAll(events));
    }

    private Timer findTimer(String aggregateType, String eventType, String result) {
        Timer timer =
                registry.find(MeteredOutbox.PUBLISH_TIMER)
                        .tags(
                                Tags.of(
                                        "aggregate_type", aggregateType,
                                        "event_type", eventType,
                                        "result", result))
                        .timer();
        assertThat(timer).as("timer for %s/%s/%s", aggregateType, eventType, result).isNotNull();
        return timer;
    }

    private static OutboxEvent event(String aggregateType, String eventType, byte[] payload) {
        return OutboxEvent.builder()
                .aggregateType(aggregateType)
                .aggregateId("agg-1")
                .eventType(eventType)
                .contentType("application/json")
                .payload(payload)
                .occurredAt(Instant.parse("2026-03-10T08:30:00Z"))
                .build();
    }

    private static final class RecordingOutbox implements Outbox {
        private OutboxEvent captured;
        private final List<OutboxEvent> batchCaptured = new ArrayList<>();
        private RuntimeException failWith;

        @Override
        public void publish(OutboxEvent event) {
            captured = event;
            if (failWith != null) {
                throw failWith;
            }
        }

        @Override
        public void publishAll(Iterable<OutboxEvent> events) {
            for (OutboxEvent e : events) {
                batchCaptured.add(e);
            }
            if (failWith != null) {
                throw failWith;
            }
        }
    }
}
