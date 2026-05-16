/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox.jdbc.spi;

/**
 * Optional database features an {@link OutboxDialect} may declare. Used by callers that need to
 * adapt behavior to dialect capabilities (e.g. tests asserting that idempotent inserts are
 * supported).
 *
 * <p>This enum is intentionally small — it lists only capabilities that affect observable behavior
 * of the publisher, not every JDBC corner case.
 *
 * @since 0.1.0
 */
public enum DialectCapability {

    /**
     * The dialect implements idempotent inserts on duplicate {@code id} (e.g. PostgreSQL {@code ON
     * CONFLICT (id) DO NOTHING}).
     */
    UPSERT_ON_CONFLICT,

    /**
     * The dialect has a native JSON column type for {@code headers} (e.g. PostgreSQL {@code
     * jsonb}). Without this capability, {@code headers} is bound as text.
     */
    NATIVE_JSON,

    /** The dialect natively binds {@link java.util.UUID} via {@code setObject}. */
    NATIVE_UUID,

    /**
     * The dialect can persist instants with their timezone offset (e.g. PostgreSQL {@code
     * timestamptz}).
     */
    TIMESTAMP_WITH_TIMEZONE,

    /** The dialect's JDBC driver supports {@code addBatch} / {@code executeBatch}. */
    BATCH_INSERT
}
