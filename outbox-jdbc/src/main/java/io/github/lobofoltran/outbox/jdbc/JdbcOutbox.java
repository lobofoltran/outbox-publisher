/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import io.github.lobofoltran.outbox.Outbox;
import io.github.lobofoltran.outbox.OutboxConfigurationException;
import io.github.lobofoltran.outbox.OutboxEvent;
import io.github.lobofoltran.outbox.OutboxException;
import io.github.lobofoltran.outbox.jdbc.internal.DialectAutoDetector;
import io.github.lobofoltran.outbox.jdbc.internal.HeadersJsonWriter;
import io.github.lobofoltran.outbox.jdbc.internal.UuidV7Generator;
import io.github.lobofoltran.outbox.jdbc.spi.OutboxDialect;
import io.github.lobofoltran.outbox.jdbc.spi.OutboxDialectProvider;
import io.github.lobofoltran.outbox.jdbc.spi.TableRef;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JDBC implementation of {@link Outbox}. Writes one row per {@link OutboxEvent} to the configured
 * outbox table within the caller's active transaction.
 *
 * <p>{@code JdbcOutbox} is database-agnostic: every database-specific decision (idempotency clause,
 * JSON column binding, timestamp binding, SQLState classification) is delegated to an {@link
 * OutboxDialect}. The dialect is either supplied explicitly via {@link
 * Builder#dialect(OutboxDialect)} or auto-detected on first publish through {@link
 * OutboxDialectProvider} (registered via {@link java.util.ServiceLoader}).
 *
 * <p>The class is thread-safe; the dialect is resolved lazily under a double-checked volatile field
 * so the hot path stays lock-free after the first publish. Every publish/publishAll call uses a
 * fresh {@link PreparedStatement} obtained from a caller-supplied {@link Connection}. {@code
 * JdbcOutbox} never closes the connection: that is the caller's responsibility (Spring's
 * transaction manager, the caller's try-with-resources block, etc.).
 *
 * <p>Construct via {@link #builder()}.
 *
 * @since 0.1.0
 */
public final class JdbcOutbox implements Outbox {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcOutbox.class);

    private final ConnectionSupplier connectionSupplier;
    private final Clock clock;
    private final IdGenerator idGenerator;
    private final TableRef table;
    private final DialectAutoDetector autoDetector;

    private volatile OutboxDialect dialect;

    private JdbcOutbox(Builder builder) {
        this.connectionSupplier = builder.connectionSupplier;
        this.clock = builder.clock;
        this.idGenerator = builder.idGenerator;
        this.table = new TableRef(builder.schema, builder.tableName);
        this.autoDetector = DialectAutoDetector.usingServiceLoader();
        this.dialect = builder.dialect;
    }

    /**
     * Creates a fresh {@link Builder}.
     *
     * @return a new builder.
     * @since 0.1.0
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public void publish(OutboxEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        publishAll(List.of(event));
    }

    @Override
    public void publishAll(Iterable<OutboxEvent> events) {
        Objects.requireNonNull(events, "events must not be null");
        List<OutboxEvent> batch = materialize(events);
        if (batch.isEmpty()) {
            return;
        }

        try {
            Connection connection = connectionSupplier.get();
            requireManualTransaction(connection);
            OutboxDialect resolved = resolveDialect(connection);
            String sql = resolved.insertSql(table);

            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (OutboxEvent event : batch) {
                    UUID id = event.id() != null ? event.id() : idGenerator.generate(clock);
                    String headersJson = HeadersJsonWriter.toJson(event.headers());
                    resolved.bindId(statement, 1, id);
                    statement.setString(2, event.aggregateType());
                    statement.setString(3, event.aggregateId());
                    statement.setString(4, event.eventType());
                    statement.setBytes(5, event.payload());
                    statement.setString(6, event.contentType());
                    resolved.bindHeaders(statement, 7, headersJson);
                    resolved.bindOptionalString(statement, 8, event.destination());
                    resolved.bindTimestamp(statement, 9, event.occurredAt());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            LOG.debug("Persisted {} outbox event(s) in one batch", batch.size());
        } catch (SQLException ex) {
            throw translate(ex, "failed to insert " + batch.size() + " outbox event(s)");
        }
    }

    private static List<OutboxEvent> materialize(Iterable<OutboxEvent> events) {
        if (events instanceof List<OutboxEvent> list) {
            for (OutboxEvent e : list) {
                Objects.requireNonNull(e, "events must not contain null elements");
            }
            return list;
        }
        List<OutboxEvent> copy = new ArrayList<>();
        Iterator<OutboxEvent> it = events.iterator();
        while (it.hasNext()) {
            OutboxEvent next = it.next();
            Objects.requireNonNull(next, "events must not contain null elements");
            copy.add(next);
        }
        return copy;
    }

    private static void requireManualTransaction(Connection connection) throws SQLException {
        if (connection.getAutoCommit()) {
            throw new OutboxConfigurationException(
                    "Connection is in autocommit mode. JdbcOutbox must run inside the caller's"
                        + " transaction so that outbox writes commit atomically with the business"
                        + " writes. Configure your ConnectionSupplier to return a connection with"
                        + " autoCommit=false (e.g. Spring's DataSourceUtils.getConnection inside a"
                        + " @Transactional method, or a manually managed Connection where the"
                        + " caller invokes setAutoCommit(false) before publishing).");
        }
    }

    private OutboxDialect resolveDialect(Connection connection) throws SQLException {
        // Volatile read on the hot path; never enters synchronized after the first publish.
        // Concurrent first publishers may each detect a dialect — that is harmless because
        // detection is deterministic for a given connection. The last volatile write wins
        // and every reader sees an equivalent dialect from then on.
        OutboxDialect local = dialect;
        if (local == null) {
            local = autoDetector.detect(connection);
            dialect = local;
        }
        return local;
    }

    private OutboxException translate(SQLException ex, String contextMessage) {
        OutboxDialect local = dialect;
        if (local != null) {
            return local.translate(ex, contextMessage);
        }
        // No dialect resolved yet — fall back to the base type. This path is only reachable
        // when getConnection() / getMetaData() / getAutoCommit() itself fails before
        // auto-detection completes, in which case the cause is what the caller cares about.
        return new OutboxException(contextMessage, ex);
    }

    /**
     * Fluent builder for {@link JdbcOutbox}.
     *
     * <p>Defaults:
     *
     * <ul>
     *   <li>{@code tableName} = {@code "outbox"}
     *   <li>{@code schema} = unset (table is referenced unqualified)
     *   <li>{@code clock} = {@link Clock#systemUTC()}
     *   <li>{@code idGenerator} = {@link UuidV7Generator}
     *   <li>{@code dialect} = unset; resolved lazily on first publish via {@link
     *       java.util.ServiceLoader}
     * </ul>
     *
     * Only {@link #connectionSupplier(ConnectionSupplier)} is required.
     *
     * <p>Instances are not thread-safe.
     *
     * @since 0.1.0
     */
    public static final class Builder {

        private ConnectionSupplier connectionSupplier;
        private String tableName = "outbox";
        private String schema;
        private Clock clock = Clock.systemUTC();
        private IdGenerator idGenerator = new UuidV7Generator();
        private OutboxDialect dialect;

        private Builder() {}

        /**
         * Sets the {@link ConnectionSupplier} that provides the JDBC connection used by every
         * publish.
         *
         * @param newConnectionSupplier the connection supplier; never {@code null}.
         * @return this builder.
         * @since 0.1.0
         */
        public Builder connectionSupplier(ConnectionSupplier newConnectionSupplier) {
            this.connectionSupplier =
                    Objects.requireNonNull(
                            newConnectionSupplier, "connectionSupplier must not be null");
            return this;
        }

        /**
         * Sets the outbox table name. Defaults to {@code "outbox"}.
         *
         * @param newTableName the table name; never {@code null}.
         * @return this builder.
         * @since 0.1.0
         */
        public Builder tableName(String newTableName) {
            this.tableName = Objects.requireNonNull(newTableName, "tableName must not be null");
            return this;
        }

        /**
         * Sets the schema qualifier for the outbox table. When {@code null} (default) the table is
         * referenced unqualified.
         *
         * @param newSchema the schema, or {@code null}.
         * @return this builder.
         * @since 0.1.0
         */
        public Builder schema(String newSchema) {
            this.schema = newSchema;
            return this;
        }

        /**
         * Sets the {@link Clock} used by the default id generator. Defaults to {@link
         * Clock#systemUTC()}.
         *
         * @param newClock the clock; never {@code null}.
         * @return this builder.
         * @since 0.1.0
         */
        public Builder clock(Clock newClock) {
            this.clock = Objects.requireNonNull(newClock, "clock must not be null");
            return this;
        }

        /**
         * Sets the {@link IdGenerator} used to assign UUIDs to events that don't carry one.
         *
         * @param newIdGenerator the id generator; never {@code null}.
         * @return this builder.
         * @since 0.1.0
         */
        public Builder idGenerator(IdGenerator newIdGenerator) {
            this.idGenerator =
                    Objects.requireNonNull(newIdGenerator, "idGenerator must not be null");
            return this;
        }

        /**
         * Pins a specific {@link OutboxDialect}, bypassing auto-detection. Use this when running
         * against a database where no provider is registered, or when a test needs a fake dialect.
         *
         * @param newDialect the dialect; never {@code null}.
         * @return this builder.
         * @since 0.1.0
         */
        public Builder dialect(OutboxDialect newDialect) {
            this.dialect = Objects.requireNonNull(newDialect, "dialect must not be null");
            return this;
        }

        /**
         * Builds the {@link JdbcOutbox} instance.
         *
         * @return the constructed outbox.
         * @since 0.1.0
         */
        public JdbcOutbox build() {
            Objects.requireNonNull(connectionSupplier, "connectionSupplier must not be null");
            return new JdbcOutbox(this);
        }
    }
}
