/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox.otel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.github.lobofoltran.outbox.Outbox;
import io.github.lobofoltran.outbox.OutboxEvent;
import io.github.lobofoltran.outbox.OutboxException;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class TracedOutboxTest {

    @RegisterExtension static final OpenTelemetryExtension otel = OpenTelemetryExtension.create();

    private RecordingOutbox delegate;
    private Tracer tracer;
    private TracedOutbox traced;

    @BeforeEach
    void setUp() {
        delegate = new RecordingOutbox();
        tracer = otel.getOpenTelemetry().getTracer("test");
        traced = new TracedOutbox(delegate, tracer);
    }

    @Test
    void delegates_publish_to_wrapped_outbox() {
        OutboxEvent event = event("Order", "OrderPlaced", "orders.events");
        traced.publish(event);
        assertThat(delegate.captured).isEqualTo(event);
    }

    @Test
    void single_publish_records_messaging_attributes_with_destination() {
        OutboxEvent event = event("Order", "OrderPlaced", "orders.events");

        traced.publish(event);

        SpanData span = singleSpan();
        assertThat(span.getName()).isEqualTo("outbox publish");
        assertThat(span.getKind()).isEqualTo(SpanKind.PRODUCER);
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
        assertThat(span.getAttributes().get(TracedOutbox.ATTR_MESSAGING_SYSTEM))
                .isEqualTo("outbox");
        assertThat(span.getAttributes().get(TracedOutbox.ATTR_MESSAGING_OPERATION))
                .isEqualTo("publish");
        assertThat(span.getAttributes().get(TracedOutbox.ATTR_MESSAGING_MESSAGE_ID))
                .isEqualTo(event.id().toString());
        assertThat(span.getAttributes().get(TracedOutbox.ATTR_MESSAGING_DESTINATION_NAME))
                .isEqualTo("orders.events");
        assertThat(span.getAttributes().get(TracedOutbox.ATTR_OUTBOX_AGGREGATE_TYPE))
                .isEqualTo("Order");
        assertThat(span.getAttributes().get(TracedOutbox.ATTR_OUTBOX_EVENT_TYPE))
                .isEqualTo("OrderPlaced");
    }

    @Test
    void single_publish_omits_destination_attribute_when_null() {
        OutboxEvent event = event("Order", "OrderPlaced", null);

        traced.publish(event);

        SpanData span = singleSpan();
        assertThat(span.getAttributes().get(TracedOutbox.ATTR_MESSAGING_DESTINATION_NAME)).isNull();
    }

    @Test
    void single_publish_records_error_status_and_exception_when_delegate_throws() {
        OutboxException cause = new OutboxException("nope");
        delegate.failWith = cause;

        assertThatThrownBy(() -> traced.publish(event("Order", "OrderPlaced", null)))
                .isSameAs(cause);

        SpanData span = singleSpan();
        StatusData status = span.getStatus();
        assertThat(status.getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getEvents()).hasSize(1);
        assertThat(span.getEvents().get(0).getName()).isEqualTo("exception");
    }

    @Test
    void single_publish_span_is_child_of_outer_span() {
        Span outer = tracer.spanBuilder("outer").startSpan();
        try (Scope ignored = outer.makeCurrent()) {
            traced.publish(event("Order", "OrderPlaced", "orders.events"));
        } finally {
            outer.end();
        }

        List<SpanData> spans = otel.getSpans();
        SpanData publishSpan =
                spans.stream()
                        .filter(s -> s.getName().equals("outbox publish"))
                        .findFirst()
                        .orElseThrow();
        SpanData outerSpan =
                spans.stream().filter(s -> s.getName().equals("outer")).findFirst().orElseThrow();
        assertThat(publishSpan.getParentSpanId()).isEqualTo(outerSpan.getSpanId());
        assertThat(publishSpan.getTraceId()).isEqualTo(outerSpan.getTraceId());
    }

    @Test
    void publish_all_records_batch_attributes() {
        List<OutboxEvent> events =
                List.of(
                        event("Order", "OrderPlaced", "orders.events"),
                        event("Order", "OrderPlaced", "orders.events"));

        traced.publishAll(events);

        assertThat(delegate.batchCaptured).containsExactlyElementsOf(events);

        SpanData span = singleSpan();
        assertThat(span.getName()).isEqualTo("outbox publish_batch");
        assertThat(span.getKind()).isEqualTo(SpanKind.PRODUCER);
        assertThat(span.getAttributes().get(TracedOutbox.ATTR_MESSAGING_SYSTEM))
                .isEqualTo("outbox");
        assertThat(span.getAttributes().get(TracedOutbox.ATTR_MESSAGING_OPERATION))
                .isEqualTo("publish_batch");
        assertThat(span.getAttributes().get(TracedOutbox.ATTR_MESSAGING_BATCH_MESSAGE_COUNT))
                .isEqualTo(2L);
        assertThat(span.getAttributes().get(TracedOutbox.ATTR_MESSAGING_MESSAGE_ID)).isNull();
        assertThat(span.getAttributes().get(TracedOutbox.ATTR_OUTBOX_AGGREGATE_TYPE)).isNull();
    }

    @Test
    void publish_all_records_error_when_delegate_throws() {
        OutboxException cause = new OutboxException("nope");
        delegate.failWith = cause;

        assertThatThrownBy(() -> traced.publishAll(List.of(event("Order", "OrderPlaced", null))))
                .isSameAs(cause);

        SpanData span = singleSpan();
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    }

    @Test
    void rejects_null_delegate() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TracedOutbox(null, tracer))
                .withMessageContaining("delegate");
    }

    @Test
    void rejects_null_tracer() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TracedOutbox(delegate, (Tracer) null))
                .withMessageContaining("tracer");
    }

    @Test
    void otel_constructor_emits_spans_on_the_libraries_instrumentation_scope() {
        TracedOutbox tracedFromOtel = new TracedOutbox(delegate, otel.getOpenTelemetry());

        tracedFromOtel.publish(event("Order", "OrderPlaced", "orders.events"));

        SpanData span = singleSpan();
        InstrumentationScopeInfo scope = span.getInstrumentationScopeInfo();
        assertThat(scope.getName()).isEqualTo(TracedOutbox.INSTRUMENTATION_NAME);
        // The version is whatever `Package.getImplementationVersion()` reports; in the in-reactor
        // test build there is typically no manifest so it falls back to "unknown". The contract
        // we pin is "the scope is set, the version is non-null", not the literal value.
        assertThat(scope.getVersion()).isNotNull();
    }

    @Test
    void otel_constructor_rejects_null_otel() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TracedOutbox(delegate, (OpenTelemetry) null))
                .withMessageContaining("otel");
    }

    @Test
    void otel_constructor_rejects_null_delegate() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TracedOutbox(null, otel.getOpenTelemetry()))
                .withMessageContaining("delegate");
    }

    @Test
    void publish_rejects_null_event() {
        assertThatNullPointerException()
                .isThrownBy(() -> traced.publish(null))
                .withMessageContaining("event");
    }

    @Test
    void publish_all_rejects_null_iterable() {
        assertThatNullPointerException()
                .isThrownBy(() -> traced.publishAll(null))
                .withMessageContaining("events");
    }

    @Test
    void publish_all_rejects_null_element() {
        List<OutboxEvent> events = new ArrayList<>();
        events.add(event("Order", "OrderPlaced", null));
        events.add(null);
        assertThatNullPointerException().isThrownBy(() -> traced.publishAll(events));
    }

    private SpanData singleSpan() {
        List<SpanData> spans = otel.getSpans();
        assertThat(spans).hasSize(1);
        return spans.get(0);
    }

    private static OutboxEvent event(String aggregateType, String eventType, String destination) {
        OutboxEvent.Builder builder =
                OutboxEvent.builder()
                        .id(UUID.randomUUID())
                        .aggregateType(aggregateType)
                        .aggregateId("agg-1")
                        .eventType(eventType)
                        .contentType("application/json")
                        .payload(new byte[] {1, 2, 3})
                        .occurredAt(Instant.parse("2026-03-10T08:30:00Z"));
        if (destination != null) {
            builder.destination(destination);
        }
        return builder.build();
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
