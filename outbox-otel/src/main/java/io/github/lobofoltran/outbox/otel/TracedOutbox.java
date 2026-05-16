/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox.otel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.github.lobofoltran.outbox.Outbox;
import io.github.lobofoltran.outbox.OutboxEvent;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;

/**
 * Decorator that wraps every {@link Outbox#publish publish} and {@link Outbox#publishAll
 * publishAll} call in an OpenTelemetry span using the {@code messaging.*} semantic conventions, and
 * injects the active trace context into the event's {@link OutboxEvent#headers() headers} so
 * downstream consumers (relay / CDC / broker) can create a linked {@code CONSUMER} span from the
 * event alone.
 *
 * <p>Spans emitted:
 *
 * <ul>
 *   <li>{@code outbox publish} — single event publish. Span kind {@link SpanKind#PRODUCER}.
 *       Attributes: {@code messaging.system=outbox}, {@code messaging.operation=publish}, {@code
 *       messaging.message.id}, {@code messaging.destination.name} (when non-null), {@code
 *       outbox.aggregate_type}, {@code outbox.event_type}.
 *   <li>{@code outbox publish_batch} — batch publish. Span kind {@link SpanKind#PRODUCER}.
 *       Attributes: {@code messaging.system=outbox}, {@code messaging.operation=publish_batch},
 *       {@code messaging.batch.message_count}. Each event in the batch carries its own injected
 *       context — the same parent span, but the values written by the propagator are computed per
 *       event so a broker that links one consumer span per message remains correct.
 * </ul>
 *
 * <p>By design, neither {@code aggregate_id} nor {@code destination} appears in the span name
 * (low-cardinality only). The same cardinality rules that govern metrics in ADR-0004 apply to span
 * names here — see ADR-0014.
 *
 * <p>Trace-context propagation defaults to the W3C {@code traceparent} / {@code tracestate} format
 * via {@link W3CTraceContextPropagator#getInstance()}. Callers needing a different wire format (B3,
 * Jaeger) can pass an explicit {@link TextMapPropagator} to the matching constructor — or, when
 * wiring through {@link #TracedOutbox(Outbox, OpenTelemetry)}, the propagator is sourced from
 * {@link OpenTelemetry#getPropagators()} so it follows the application's global SDK configuration.
 *
 * <p>Headers injected by the propagator never overwrite headers the caller already set on the
 * {@link OutboxEvent}: caller-supplied keys win. This lets a caller carrying a trace context from
 * another transport (e.g. an inbound HTTP request whose {@code traceparent} should be preserved
 * verbatim) pass it through unchanged.
 *
 * <p>On exception, the span status is set to {@link StatusCode#ERROR} and the exception is recorded
 * via {@link Span#recordException(Throwable)}; the original exception is rethrown unchanged.
 *
 * <p>Instances are thread-safe: the {@link Tracer} and {@link TextMapPropagator} contracts permit
 * concurrent use, and the decorator holds no mutable state of its own.
 *
 * @since 0.1.0
 */
public final class TracedOutbox implements Outbox {

    static final String SPAN_NAME_PUBLISH = "outbox publish";
    static final String SPAN_NAME_PUBLISH_BATCH = "outbox publish_batch";

    static final AttributeKey<String> ATTR_MESSAGING_SYSTEM =
            AttributeKey.stringKey("messaging.system");
    static final AttributeKey<String> ATTR_MESSAGING_OPERATION =
            AttributeKey.stringKey("messaging.operation");
    static final AttributeKey<String> ATTR_MESSAGING_DESTINATION_NAME =
            AttributeKey.stringKey("messaging.destination.name");
    static final AttributeKey<String> ATTR_MESSAGING_MESSAGE_ID =
            AttributeKey.stringKey("messaging.message.id");
    static final AttributeKey<Long> ATTR_MESSAGING_BATCH_MESSAGE_COUNT =
            AttributeKey.longKey("messaging.batch.message_count");
    static final AttributeKey<String> ATTR_OUTBOX_AGGREGATE_TYPE =
            AttributeKey.stringKey("outbox.aggregate_type");
    static final AttributeKey<String> ATTR_OUTBOX_EVENT_TYPE =
            AttributeKey.stringKey("outbox.event_type");

    private static final String MESSAGING_SYSTEM = "outbox";
    private static final String OPERATION_PUBLISH = "publish";
    private static final String OPERATION_PUBLISH_BATCH = "publish_batch";

    /**
     * Instrumentation scope name reported on every span emitted by this decorator. Stable across
     * versions so backends can group all {@code outbox-otel} spans under a single library entry.
     */
    static final String INSTRUMENTATION_NAME = "io.github.lobofoltran.outbox";

    static final String UNKNOWN_VERSION = "unknown";

    /**
     * Setter used to inject propagated context entries into a plain mutable {@link Map}. Stateless
     * — the propagator hands us back the carrier we gave it, populated with the wire-format entries
     * (e.g. {@code traceparent}, {@code tracestate}).
     */
    private static final TextMapSetter<Map<String, String>> MAP_SETTER = Map::put;

    private final Outbox delegate;
    private final Tracer tracer;
    private final TextMapPropagator propagator;

    /**
     * Wraps {@code delegate} so every publish call emits a span on the supplied {@link Tracer} and
     * propagates trace context via the W3C {@code traceparent} / {@code tracestate} headers.
     *
     * <p>This is the lowest-level constructor; the caller is responsible for obtaining the {@code
     * Tracer} from its own {@code TracerProvider}, including setting an instrumentation scope name
     * and version that fit its observability conventions. Prefer {@link #TracedOutbox(Outbox,
     * OpenTelemetry)} when those defaults are acceptable.
     *
     * @param delegate the underlying {@link Outbox}; never {@code null}.
     * @param tracer the {@link Tracer} to record spans on; never {@code null}.
     * @since 0.1.0
     */
    public TracedOutbox(Outbox delegate, Tracer tracer) {
        this(delegate, tracer, W3CTraceContextPropagator.getInstance());
    }

    /**
     * Wraps {@code delegate} so every publish call emits a span on the supplied {@link Tracer} and
     * propagates trace context using the supplied {@link TextMapPropagator}. Use this constructor
     * to opt into B3, Jaeger, or any composite propagator the application has standardized on.
     *
     * @param delegate the underlying {@link Outbox}; never {@code null}.
     * @param tracer the {@link Tracer} to record spans on; never {@code null}.
     * @param propagator the {@link TextMapPropagator} used to inject context into event headers;
     *     never {@code null}.
     * @since 0.2.0
     */
    public TracedOutbox(Outbox delegate, Tracer tracer, TextMapPropagator propagator) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.tracer = Objects.requireNonNull(tracer, "tracer must not be null");
        this.propagator = Objects.requireNonNull(propagator, "propagator must not be null");
    }

    /**
     * Convenience constructor that builds a {@link Tracer} from {@code otel} pinned to the {@code
     * outbox-otel} instrumentation scope ({@value #INSTRUMENTATION_NAME}) and to this library's
     * published version (read from {@link Package#getImplementationVersion()}, falling back to
     * {@value #UNKNOWN_VERSION} when the JAR is exploded on the classpath without a manifest —
     * typically only the case in IDE / reactor builds). The {@link TextMapPropagator} is taken from
     * {@link OpenTelemetry#getPropagators()} so it follows the application's globally configured
     * propagation format.
     *
     * <p>The Spring Boot auto-configuration uses this constructor so the instrumentation scope name
     * and version follow the library, not the application. Manual wiring code should prefer this
     * constructor too unless the caller needs a non-default tracer or propagator.
     *
     * @param delegate the underlying {@link Outbox}; never {@code null}.
     * @param otel the {@link OpenTelemetry} used to obtain a {@link Tracer} and the propagator;
     *     never {@code null}.
     * @since 0.1.0
     */
    public TracedOutbox(Outbox delegate, OpenTelemetry otel) {
        this(
                delegate,
                Objects.requireNonNull(otel, "otel must not be null")
                        .getTracerProvider()
                        .tracerBuilder(INSTRUMENTATION_NAME)
                        .setInstrumentationVersion(versionOrUnknown())
                        .build(),
                otel.getPropagators().getTextMapPropagator());
    }

    /**
     * Reads the implementation version from {@code TracedOutbox}'s own {@link Package}, falling
     * back to {@value #UNKNOWN_VERSION} when the {@code Implementation-Version} manifest entry is
     * absent (exploded JAR / IDE incremental build / in-reactor test build). The fallback is silent
     * on purpose: a missing version is not worth failing instrumentation over.
     *
     * <p>{@link Class#getPackage()} returns non-null for every non-array, non-primitive type, so
     * {@code TracedOutbox.class.getPackage()} is unconditionally safe — no null-check on the
     * package itself is needed.
     */
    private static String versionOrUnknown() {
        return Objects.requireNonNullElse(
                TracedOutbox.class.getPackage().getImplementationVersion(), UNKNOWN_VERSION);
    }

    @Override
    public void publish(OutboxEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        Span span =
                tracer.spanBuilder(SPAN_NAME_PUBLISH)
                        .setSpanKind(SpanKind.PRODUCER)
                        .setAttribute(ATTR_MESSAGING_SYSTEM, MESSAGING_SYSTEM)
                        .setAttribute(ATTR_MESSAGING_OPERATION, OPERATION_PUBLISH)
                        .setAttribute(ATTR_OUTBOX_AGGREGATE_TYPE, event.aggregateType())
                        .setAttribute(ATTR_OUTBOX_EVENT_TYPE, event.eventType())
                        .startSpan();
        // Id ownership belongs to JdbcOutbox: when the caller leaves id=null, the JDBC layer
        // generates one. The decorator runs before that, so we simply omit the attribute
        // rather than fabricate a value that would not match the row eventually written.
        if (event.id() != null) {
            span.setAttribute(ATTR_MESSAGING_MESSAGE_ID, event.id().toString());
        }
        if (event.destination() != null) {
            span.setAttribute(ATTR_MESSAGING_DESTINATION_NAME, event.destination());
        }
        runUnderSpan(span, () -> delegate.publish(withInjectedContext(event)));
    }

    @Override
    public void publishAll(Iterable<OutboxEvent> events) {
        Objects.requireNonNull(events, "events must not be null");
        // Materialize once so the count is known up-front and so single-pass iterables work.
        List<OutboxEvent> batch = new ArrayList<>();
        for (OutboxEvent event : events) {
            Objects.requireNonNull(event, "events must not contain null elements");
            batch.add(event);
        }

        Span span =
                tracer.spanBuilder(SPAN_NAME_PUBLISH_BATCH)
                        .setSpanKind(SpanKind.PRODUCER)
                        .setAttribute(ATTR_MESSAGING_SYSTEM, MESSAGING_SYSTEM)
                        .setAttribute(ATTR_MESSAGING_OPERATION, OPERATION_PUBLISH_BATCH)
                        .setAttribute(ATTR_MESSAGING_BATCH_MESSAGE_COUNT, (long) batch.size())
                        .startSpan();
        runUnderSpan(
                span,
                () -> {
                    // Inject context once the batch span is current so every event in the batch
                    // ends up parented to the same producer span on the consumer side.
                    List<OutboxEvent> injected = new ArrayList<>(batch.size());
                    for (OutboxEvent event : batch) {
                        injected.add(withInjectedContext(event));
                    }
                    delegate.publishAll(injected);
                });
    }

    /**
     * Returns a copy of {@code event} whose headers carry the active trace context, or the same
     * instance when the propagator wrote nothing (e.g. no current span / invalid context). Headers
     * supplied by the caller always win over auto-injected ones — this lets a service that already
     * received a {@code traceparent} on its inbound edge forward it verbatim instead of replacing
     * it with the context of the publish-time span.
     */
    private OutboxEvent withInjectedContext(OutboxEvent event) {
        Map<String, String> injected = new LinkedHashMap<>();
        propagator.inject(Context.current(), injected, MAP_SETTER);
        if (injected.isEmpty()) {
            return event;
        }
        Map<String, String> existing = event.headers();
        Map<String, String> merged = new LinkedHashMap<>(injected.size() + existing.size());
        merged.putAll(injected);
        // Caller-supplied keys overlay the injected ones, by design.
        merged.putAll(existing);
        return new OutboxEvent(
                event.id(),
                event.aggregateType(),
                event.aggregateId(),
                event.eventType(),
                event.contentType(),
                event.payload(),
                merged,
                event.destination(),
                event.occurredAt());
    }

    /**
     * Runs the action with the given span made current, recording any escaping exception on the
     * span before rethrowing it. {@link RuntimeException} is the widest type that can escape a
     * {@link Outbox#publish} or {@link Outbox#publishAll} call; checkstyle's {@code IllegalCatch}
     * rule is suppressed here for that reason — narrowing further would silently lose error
     * recording for legitimate unchecked exceptions thrown by buggy delegates.
     */
    @SuppressWarnings("checkstyle:IllegalCatch")
    private static void runUnderSpan(Span span, Runnable action) {
        try (Scope ignored = span.makeCurrent()) {
            action.run();
        } catch (RuntimeException ex) {
            span.recordException(ex);
            span.setStatus(StatusCode.ERROR, ex.getClass().getSimpleName());
            throw ex;
        } finally {
            span.end();
        }
    }
}
