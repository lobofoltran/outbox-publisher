package io.github.lobofoltran.outbox.micrometer;

import java.util.Objects;

import io.github.lobofoltran.outbox.Outbox;
import io.github.lobofoltran.outbox.OutboxEvent;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Decorator that adds Micrometer instrumentation to every {@link Outbox#publish publish} call
 * without changing the contract of the underlying {@link Outbox}.
 *
 * <p>Meters emitted:
 *
 * <ul>
 *   <li>{@code outbox.publish} — a {@link Timer} recording publish latency. Tags: {@code
 *       aggregate_type}, {@code event_type}, {@code result} ({@code success} or {@code failure}).
 *   <li>{@code outbox.publish.bytes} — a {@link DistributionSummary} recording the payload size in
 *       bytes. Tags: {@code aggregate_type}, {@code event_type}.
 * </ul>
 *
 * <p>By design, {@code aggregate_id} and {@code destination} are <em>never</em> tagged, to keep
 * metric cardinality bounded — see ADR-0004 for the rationale.
 *
 * <p>Instances are thread-safe: the {@link MeterRegistry} contract guarantees concurrent meter
 * registration is safe, and the decorator holds no mutable state of its own.
 */
public final class MeteredOutbox implements Outbox {

    static final String PUBLISH_TIMER = "outbox.publish";
    static final String PAYLOAD_SUMMARY = "outbox.publish.bytes";

    private static final String TAG_AGGREGATE_TYPE = "aggregate_type";
    private static final String TAG_EVENT_TYPE = "event_type";
    private static final String TAG_RESULT = "result";
    private static final String RESULT_SUCCESS = "success";
    private static final String RESULT_FAILURE = "failure";

    private final Outbox delegate;
    private final MeterRegistry registry;

    public MeteredOutbox(Outbox delegate, MeterRegistry registry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    @Override
    public void publish(OutboxEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        // payload() returns a defensive copy; call it once before the timer starts so
        // the clone time is not counted as publish latency.
        int payloadBytes = event.payload().length;
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

    private Timer timer(OutboxEvent event, String result) {
        return Timer.builder(PUBLISH_TIMER)
                .tag(TAG_AGGREGATE_TYPE, event.aggregateType())
                .tag(TAG_EVENT_TYPE, event.eventType())
                .tag(TAG_RESULT, result)
                .register(registry);
    }

    private DistributionSummary payloadSummary(OutboxEvent event) {
        return DistributionSummary.builder(PAYLOAD_SUMMARY)
                .tag(TAG_AGGREGATE_TYPE, event.aggregateType())
                .tag(TAG_EVENT_TYPE, event.eventType())
                .baseUnit("bytes")
                .register(registry);
    }
}
