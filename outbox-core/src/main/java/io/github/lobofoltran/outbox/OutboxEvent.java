package io.github.lobofoltran.outbox;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One row to be written to the {@code outbox} table.
 *
 * <p>Construct instances through {@link #builder()}. The compact constructor enforces field
 * invariants and makes a defensive copy of {@code headers} and {@code payload}; the corresponding
 * accessors return immutable / fresh copies to keep the record effectively immutable.
 *
 * <p>Field semantics match the columns documented in {@code AGENTS.md > Table contract}. Length
 * limits mirror the SQL column types so callers get an early failure instead of a {@code
 * SQLException} at write time.
 *
 * <p><b>Equality:</b> the record uses the default component-wise equality. Because {@code payload}
 * is a {@code byte[]}, two events with identical bytes but distinct array instances are
 * <em>not</em> equal. This is intentional — within a process the {@code id} discriminates events,
 * and outside of tests payload-equality is not a useful operation.
 *
 * @param id optional event identifier. When {@code null}, the implementation generates one (UUIDv7
 *     in {@code outbox-jdbc}).
 * @param aggregateType non-blank, at most 128 characters.
 * @param aggregateId non-blank, at most 128 characters.
 * @param eventType non-blank, at most 128 characters.
 * @param contentType non-blank, at most 64 characters (e.g. {@code application/json}).
 * @param payload opaque bytes; the library never deserializes it.
 * @param headers string-to-string metadata; never {@code null}, may be empty.
 * @param destination optional routing hint (topic / exchange / queue), at most 128 characters when
 *     present.
 * @param occurredAt the domain timestamp; never {@code null}.
 */
public record OutboxEvent(
        UUID id,
        String aggregateType,
        String aggregateId,
        String eventType,
        String contentType,
        byte[] payload,
        Map<String, String> headers,
        String destination,
        Instant occurredAt) {

    private static final int MAX_AGGREGATE_TYPE = 128;
    private static final int MAX_AGGREGATE_ID = 128;
    private static final int MAX_EVENT_TYPE = 128;
    private static final int MAX_CONTENT_TYPE = 64;
    private static final int MAX_DESTINATION = 128;

    public OutboxEvent {
        Objects.requireNonNull(aggregateType, "aggregateType must not be null");
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(contentType, "contentType must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(headers, "headers must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");

        requireNonBlank(aggregateType, "aggregateType");
        requireNonBlank(aggregateId, "aggregateId");
        requireNonBlank(eventType, "eventType");
        requireNonBlank(contentType, "contentType");

        requireMaxLength(aggregateType, "aggregateType", MAX_AGGREGATE_TYPE);
        requireMaxLength(aggregateId, "aggregateId", MAX_AGGREGATE_ID);
        requireMaxLength(eventType, "eventType", MAX_EVENT_TYPE);
        requireMaxLength(contentType, "contentType", MAX_CONTENT_TYPE);

        if (destination != null) {
            requireMaxLength(destination, "destination", MAX_DESTINATION);
        }

        for (Map.Entry<String, String> entry : headers.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "header keys must not be null");
            Objects.requireNonNull(entry.getValue(), "header values must not be null");
        }

        payload = payload.clone();
        // LinkedHashMap preserves insertion order — useful when callers ship pairs in a
        // meaningful sequence. unmodifiableMap then prevents external mutation.
        headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
    }

    /**
     * Returns a defensive copy of {@code payload}.
     *
     * <p>Callers that only need the size in bytes should prefer {@link #payloadSize()}, which
     * avoids cloning the underlying array.
     */
    @Override
    public byte[] payload() {
        return payload.clone();
    }

    /**
     * Returns the size in bytes of the payload, without cloning the underlying array. Intended for
     * callers (metrics, logging) that need the size and never the bytes.
     */
    public int payloadSize() {
        return payload.length;
    }

    /** Returns the headers map. Already immutable; safe to return as-is. */
    @Override
    public Map<String, String> headers() {
        return headers;
    }

    /** Creates a fresh {@link Builder}. */
    public static Builder builder() {
        return new Builder();
    }

    private static void requireNonBlank(String value, String name) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireMaxLength(String value, String name, int max) {
        if (value.length() > max) {
            throw new IllegalArgumentException(
                    name + " must be at most " + max + " characters, got " + value.length());
        }
    }

    /**
     * Fluent builder for {@link OutboxEvent}.
     *
     * <p>Defaults applied at {@link #build()} time:
     *
     * <ul>
     *   <li>{@code occurredAt} defaults to {@code Instant.now()}.
     *   <li>{@code headers} defaults to an empty map.
     *   <li>{@code id} and {@code destination} default to {@code null}.
     * </ul>
     *
     * All other fields are required and validated by the record's compact constructor.
     *
     * <p>Instances are not thread-safe.
     */
    public static final class Builder {

        private UUID id;
        private String aggregateType;
        private String aggregateId;
        private String eventType;
        private String contentType;
        private byte[] payload;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private String destination;
        private Instant occurredAt;

        private Builder() {}

        public Builder id(UUID newId) {
            this.id = newId;
            return this;
        }

        public Builder aggregateType(String newAggregateType) {
            this.aggregateType = newAggregateType;
            return this;
        }

        public Builder aggregateId(String newAggregateId) {
            this.aggregateId = newAggregateId;
            return this;
        }

        public Builder eventType(String newEventType) {
            this.eventType = newEventType;
            return this;
        }

        public Builder contentType(String newContentType) {
            this.contentType = newContentType;
            return this;
        }

        public Builder payload(byte[] newPayload) {
            this.payload = newPayload;
            return this;
        }

        /** Replaces all previously accumulated headers with a copy of {@code newHeaders}. */
        public Builder headers(Map<String, String> newHeaders) {
            Objects.requireNonNull(newHeaders, "headers must not be null");
            this.headers.clear();
            this.headers.putAll(newHeaders);
            return this;
        }

        /** Adds (or overrides) a single header pair. */
        public Builder header(String key, String value) {
            Objects.requireNonNull(key, "header key must not be null");
            Objects.requireNonNull(value, "header value must not be null");
            this.headers.put(key, value);
            return this;
        }

        public Builder destination(String newDestination) {
            this.destination = newDestination;
            return this;
        }

        public Builder occurredAt(Instant newOccurredAt) {
            this.occurredAt = newOccurredAt;
            return this;
        }

        public OutboxEvent build() {
            Instant resolvedOccurredAt = occurredAt != null ? occurredAt : Instant.now();
            Map<String, String> snapshot = new LinkedHashMap<>(headers);
            return new OutboxEvent(
                    id,
                    aggregateType,
                    aggregateId,
                    eventType,
                    contentType,
                    payload,
                    snapshot,
                    destination,
                    resolvedOccurredAt);
        }
    }
}
