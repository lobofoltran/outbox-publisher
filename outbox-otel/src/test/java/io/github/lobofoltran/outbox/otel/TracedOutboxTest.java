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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.lobofoltran.outbox.Outbox;
import io.github.lobofoltran.outbox.OutboxEvent;
import io.github.lobofoltran.outbox.OutboxException;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
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
        // The decorator injects trace-context headers, so the captured event is not byte-equal to
        // the input — but every identity-bearing field must still match.
        assertThat(delegate.captured.id()).isEqualTo(event.id());
        assertThat(delegate.captured.aggregateType()).isEqualTo(event.aggregateType());
        assertThat(delegate.captured.aggregateId()).isEqualTo(event.aggregateId());
        assertThat(delegate.captured.eventType()).isEqualTo(event.eventType());
        assertThat(delegate.captured.destination()).isEqualTo(event.destination());
        assertThat(delegate.captured.occurredAt()).isEqualTo(event.occurredAt());
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
    void single_publish_omits_message_id_attribute_when_id_is_null() {
        OutboxEvent event = eventWithoutId("Order", "OrderPlaced", "orders.events");

        traced.publish(event);

        // The decorator augments headers with trace context, so the captured event is a fresh
        // copy — id() stays null and identity-bearing fields are preserved.
        assertThat(delegate.captured.id()).isNull();
        assertThat(delegate.captured.aggregateType()).isEqualTo(event.aggregateType());
        assertThat(delegate.captured.eventType()).isEqualTo(event.eventType());
        SpanData span = singleSpan();
        assertThat(span.getAttributes().get(TracedOutbox.ATTR_MESSAGING_MESSAGE_ID)).isNull();
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

        // The decorator rewrites each event to add trace-context headers; identity is preserved.
        assertThat(delegate.batchCaptured).hasSize(events.size());
        assertThat(delegate.batchCaptured.stream().map(OutboxEvent::id).toList())
                .containsExactlyElementsOf(events.stream().map(OutboxEvent::id).toList());

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

    @Test
    void publish_injects_traceparent_into_event_headers() {
        Span outer = tracer.spanBuilder("outer").startSpan();
        try (Scope ignored = outer.makeCurrent()) {
            traced.publish(event("Order", "OrderPlaced", "orders.events"));
        } finally {
            outer.end();
        }

        Map<String, String> headers = delegate.captured.headers();
        assertThat(headers).containsKey("traceparent");
        // The header must encode the trace id of the publish span, which is the child of `outer`
        // and therefore shares its trace id.
        SpanData publishSpan =
                otel.getSpans().stream()
                        .filter(s -> s.getName().equals("outbox publish"))
                        .findFirst()
                        .orElseThrow();
        SpanContext extracted = extractSpanContext(headers);
        assertThat(extracted.getTraceId()).isEqualTo(publishSpan.getTraceId());
        assertThat(extracted.getSpanId()).isEqualTo(publishSpan.getSpanId());
    }

    @Test
    void publish_preserves_caller_supplied_traceparent_header() {
        // A non-current, syntactically valid traceparent the caller wants to forward verbatim.
        String callerTraceparent = "00-0123456789abcdef0123456789abcdef-fedcba9876543210-01";
        OutboxEvent event =
                OutboxEvent.builder()
                        .id(UUID.randomUUID())
                        .aggregateType("Order")
                        .aggregateId("agg-1")
                        .eventType("OrderPlaced")
                        .contentType("application/json")
                        .payload(new byte[] {1})
                        .destination("orders.events")
                        .header("traceparent", callerTraceparent)
                        .occurredAt(Instant.parse("2026-03-10T08:30:00Z"))
                        .build();

        Span outer = tracer.spanBuilder("outer").startSpan();
        try (Scope ignored = outer.makeCurrent()) {
            traced.publish(event);
        } finally {
            outer.end();
        }

        assertThat(delegate.captured.headers()).containsEntry("traceparent", callerTraceparent);
    }

    @Test
    void publish_all_injects_traceparent_into_every_event_in_a_non_singleton_iterable() {
        OutboxEvent a = event("Order", "OrderPlaced", "orders.events");
        OutboxEvent b = event("Order", "OrderPlaced", "orders.events");
        // Single-pass iterable to prove the decorator materializes the batch and still injects
        // context into each element.
        Iterable<OutboxEvent> singlePass = List.of(a, b)::iterator;

        Span outer = tracer.spanBuilder("outer").startSpan();
        try (Scope ignored = outer.makeCurrent()) {
            traced.publishAll(singlePass);
        } finally {
            outer.end();
        }

        SpanData batchSpan =
                otel.getSpans().stream()
                        .filter(s -> s.getName().equals("outbox publish_batch"))
                        .findFirst()
                        .orElseThrow();
        assertThat(delegate.batchCaptured).hasSize(2);
        for (OutboxEvent captured : delegate.batchCaptured) {
            assertThat(captured.headers()).containsKey("traceparent");
            SpanContext extracted = extractSpanContext(captured.headers());
            assertThat(extracted.getTraceId()).isEqualTo(batchSpan.getTraceId());
            assertThat(extracted.getSpanId()).isEqualTo(batchSpan.getSpanId());
        }
    }

    @Test
    void custom_propagator_is_used_when_supplied() {
        TextMapPropagator recording =
                new TextMapPropagator() {
                    @Override
                    public Collection<String> fields() {
                        return List.of("x-custom-trace");
                    }

                    @Override
                    public <C> void inject(Context context, C carrier, TextMapSetter<C> setter) {
                        setter.set(carrier, "x-custom-trace", "abc123");
                    }

                    @Override
                    public <C> Context extract(
                            Context context, C carrier, TextMapGetter<C> getter) {
                        return context;
                    }
                };
        TracedOutbox customTraced = new TracedOutbox(delegate, tracer, recording);

        customTraced.publish(event("Order", "OrderPlaced", "orders.events"));

        assertThat(delegate.captured.headers()).containsEntry("x-custom-trace", "abc123");
        assertThat(delegate.captured.headers()).doesNotContainKey("traceparent");
    }

    @Test
    void propagator_that_injects_nothing_leaves_headers_untouched() {
        // Mirrors the "no current context" case: the propagator writes no entries, so the event
        // must be forwarded unchanged (headers stay empty, no rebuild needed).
        TextMapPropagator noop =
                new TextMapPropagator() {
                    @Override
                    public Collection<String> fields() {
                        return List.of();
                    }

                    @Override
                    public <C> void inject(Context context, C carrier, TextMapSetter<C> setter) {
                        // intentionally empty
                    }

                    @Override
                    public <C> Context extract(
                            Context context, C carrier, TextMapGetter<C> getter) {
                        return context;
                    }
                };
        TracedOutbox quiet = new TracedOutbox(delegate, tracer, noop);
        OutboxEvent event = event("Order", "OrderPlaced", "orders.events");

        quiet.publish(event);

        assertThat(delegate.captured).isSameAs(event);
    }

    @Test
    void rejects_null_propagator() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TracedOutbox(delegate, tracer, null))
                .withMessageContaining("propagator");
    }

    private static SpanContext extractSpanContext(Map<String, String> headers) {
        TextMapGetter<Map<String, String>> getter =
                new TextMapGetter<>() {
                    @Override
                    public Iterable<String> keys(Map<String, String> carrier) {
                        return carrier.keySet();
                    }

                    @Override
                    public String get(Map<String, String> carrier, String key) {
                        return carrier == null ? null : carrier.get(key);
                    }
                };
        Context extracted =
                W3CTraceContextPropagator.getInstance().extract(Context.root(), headers, getter);
        return Span.fromContext(extracted).getSpanContext();
    }

    private SpanData singleSpan() {
        List<SpanData> spans = otel.getSpans();
        assertThat(spans).hasSize(1);
        return spans.get(0);
    }

    private static OutboxEvent eventWithoutId(
            String aggregateType, String eventType, String destination) {
        OutboxEvent.Builder builder =
                OutboxEvent.builder()
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
