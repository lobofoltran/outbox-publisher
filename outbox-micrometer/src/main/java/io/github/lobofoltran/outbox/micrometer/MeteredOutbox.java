/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox.micrometer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiPredicate;

import io.github.lobofoltran.outbox.Outbox;
import io.github.lobofoltran.outbox.OutboxEvent;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Decorator that adds Micrometer instrumentation to every {@link Outbox#publish publish} and {@link
 * Outbox#publishAll publishAll} call without changing the contract of the underlying {@link
 * Outbox}.
 *
 * <p>Meters emitted:
 *
 * <ul>
 *   <li>{@code outbox.publish} — a {@link Timer} recording publish latency (single-event path).
 *       Tags: {@code aggregate_type}, {@code event_type}, {@code result} ({@code success} or {@code
 *       failure}).
 *   <li>{@code outbox.publish.bytes} — a {@link DistributionSummary} recording per-event payload
 *       size in bytes. Tags: {@code aggregate_type}, {@code event_type}.
 *   <li>{@code outbox.publish.batch} — a {@link Timer} recording the latency of one {@code
 *       publishAll} call across the whole batch. Tag: {@code result} only.
 *   <li>{@code outbox.publish.batch.size} — a {@link DistributionSummary} recording the number of
 *       events per {@code publishAll} call. Untagged.
 * </ul>
 *
 * <p>By design, {@code aggregate_id} and {@code destination} are <em>never</em> tagged, to keep
 * metric cardinality bounded.
 *
 * <h2>Cardinality cap</h2>
 *
 * <p>{@code aggregate_type} and {@code event_type} are emitted as tag values. If a caller derives
 * either from user input the registry will allocate one time series per unique value, which is the
 * canonical Micrometer cardinality bomb. To defend against this at runtime the decorator accepts an
 * optional {@link BiPredicate} that decides whether a tag value should be kept; values that fail
 * the predicate are replaced with a configurable fallback string (default {@code "other"}). The
 * predicate is invoked once per meter registration with the tuple {@code (tagName, value)}, where
 * {@code tagName} is {@link #TAG_AGGREGATE_TYPE} or {@link #TAG_EVENT_TYPE}. The {@code result} tag
 * is never sanitized — its domain is fixed by the library.
 *
 * <p>The default predicate ({@code (n, v) -> true}) preserves the pre-existing behavior, so
 * existing callers see no change.
 *
 * <p>Instances are thread-safe: the {@link MeterRegistry} contract guarantees concurrent meter
 * registration is safe, and the decorator holds no mutable state of its own.
 *
 * @since 0.1.0
 */
public final class MeteredOutbox implements Outbox {

    static final String PUBLISH_TIMER = "outbox.publish";
    static final String PAYLOAD_SUMMARY = "outbox.publish.bytes";
    static final String BATCH_TIMER = "outbox.publish.batch";
    static final String BATCH_SIZE_SUMMARY = "outbox.publish.batch.size";

    static final String TAG_AGGREGATE_TYPE = "aggregate_type";
    static final String TAG_EVENT_TYPE = "event_type";
    private static final String TAG_RESULT = "result";
    private static final String RESULT_SUCCESS = "success";
    private static final String RESULT_FAILURE = "failure";

    /**
     * Default replacement for tag values that fail the cardinality predicate.
     *
     * @since 0.2.0
     */
    public static final String DEFAULT_TAG_FALLBACK = "other";

    private static final BiPredicate<String, String> ALLOW_ALL = (name, value) -> true;

    private final Outbox delegate;
    private final MeterRegistry registry;
    private final BiPredicate<String, String> tagValuePredicate;
    private final String tagFallback;

    /**
     * Creates a new metering decorator around {@code delegate} with the default pass-through tag
     * policy.
     *
     * @param delegate the underlying {@link Outbox}; never {@code null}.
     * @param registry the {@link MeterRegistry} that receives metrics; never {@code null}.
     * @since 0.1.0
     */
    public MeteredOutbox(Outbox delegate, MeterRegistry registry) {
        this(delegate, registry, ALLOW_ALL, DEFAULT_TAG_FALLBACK);
    }

    /**
     * Creates a new metering decorator around {@code delegate} with a custom tag-cardinality
     * policy.
     *
     * @param delegate the underlying {@link Outbox}; never {@code null}.
     * @param registry the {@link MeterRegistry} that receives metrics; never {@code null}.
     * @param tagValuePredicate predicate that decides whether a {@code (tagName, value)} pair is
     *     allowed onto the registry. Values that return {@code false} are replaced with {@code
     *     tagFallback}. Never {@code null}.
     * @param tagFallback the replacement string used when {@code tagValuePredicate} rejects a
     *     value. Never {@code null} or blank.
     * @since 0.2.0
     */
    public MeteredOutbox(
            Outbox delegate,
            MeterRegistry registry,
            BiPredicate<String, String> tagValuePredicate,
            String tagFallback) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.tagValuePredicate =
                Objects.requireNonNull(tagValuePredicate, "tagValuePredicate must not be null");
        Objects.requireNonNull(tagFallback, "tagFallback must not be null");
        if (tagFallback.isBlank()) {
            throw new IllegalArgumentException("tagFallback must not be blank");
        }
        this.tagFallback = tagFallback;
    }

    @Override
    public void publish(OutboxEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        // payloadSize() returns the byte length without cloning the underlying array,
        // so we pay no allocation on the hot publish path.
        int payloadBytes = event.payloadSize();
        Timer.Sample sample = Timer.start(registry);
        boolean success = false;
        try {
            delegate.publish(event);
            success = true;
        } finally {
            sample.stop(timer(event, success ? RESULT_SUCCESS : RESULT_FAILURE));
            payloadSummary(event).record(payloadBytes);
        }
    }

    @Override
    public void publishAll(Iterable<OutboxEvent> events) {
        Objects.requireNonNull(events, "events must not be null");
        // Materialize once so we can record metrics regardless of the underlying implementation
        // and so that single-pass iterables work as expected.
        List<OutboxEvent> batch = new ArrayList<>();
        for (OutboxEvent event : events) {
            Objects.requireNonNull(event, "events must not contain null elements");
            batch.add(event);
        }

        Timer.Sample sample = Timer.start(registry);
        boolean success = false;
        try {
            delegate.publishAll(batch);
            success = true;
        } finally {
            sample.stop(batchTimer(success ? RESULT_SUCCESS : RESULT_FAILURE));
            batchSizeSummary().record(batch.size());
            for (OutboxEvent event : batch) {
                payloadSummary(event).record(event.payloadSize());
            }
        }
    }

    private Timer timer(OutboxEvent event, String result) {
        return Timer.builder(PUBLISH_TIMER)
                .tag(TAG_AGGREGATE_TYPE, sanitize(TAG_AGGREGATE_TYPE, event.aggregateType()))
                .tag(TAG_EVENT_TYPE, sanitize(TAG_EVENT_TYPE, event.eventType()))
                .tag(TAG_RESULT, result)
                .register(registry);
    }

    private Timer batchTimer(String result) {
        return Timer.builder(BATCH_TIMER).tag(TAG_RESULT, result).register(registry);
    }

    private DistributionSummary batchSizeSummary() {
        return DistributionSummary.builder(BATCH_SIZE_SUMMARY).register(registry);
    }

    private DistributionSummary payloadSummary(OutboxEvent event) {
        return DistributionSummary.builder(PAYLOAD_SUMMARY)
                .tag(TAG_AGGREGATE_TYPE, sanitize(TAG_AGGREGATE_TYPE, event.aggregateType()))
                .tag(TAG_EVENT_TYPE, sanitize(TAG_EVENT_TYPE, event.eventType()))
                .baseUnit("bytes")
                .register(registry);
    }

    private String sanitize(String tagName, String value) {
        return tagValuePredicate.test(tagName, value) ? value : tagFallback;
    }
}
