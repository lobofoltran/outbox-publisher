/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox.micrometer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
 * metric cardinality bounded — see ADR-0004 for the rationale.
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

    private static final String TAG_AGGREGATE_TYPE = "aggregate_type";
    private static final String TAG_EVENT_TYPE = "event_type";
    private static final String TAG_RESULT = "result";
    private static final String RESULT_SUCCESS = "success";
    private static final String RESULT_FAILURE = "failure";

    private final Outbox delegate;
    private final MeterRegistry registry;

    /**
     * Creates a new metering decorator around {@code delegate}.
     *
     * @param delegate the underlying {@link Outbox}; never {@code null}.
     * @param registry the {@link MeterRegistry} that receives metrics; never {@code null}.
     * @since 0.1.0
     */
    public MeteredOutbox(Outbox delegate, MeterRegistry registry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
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
                .tag(TAG_AGGREGATE_TYPE, event.aggregateType())
                .tag(TAG_EVENT_TYPE, event.eventType())
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
                .tag(TAG_AGGREGATE_TYPE, event.aggregateType())
                .tag(TAG_EVENT_TYPE, event.eventType())
                .baseUnit("bytes")
                .register(registry);
    }
}
