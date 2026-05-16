package io.github.lobofoltran.outbox.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import io.github.lobofoltran.outbox.Outbox;
import io.github.lobofoltran.outbox.OutboxEvent;
import io.github.lobofoltran.outbox.OutboxException;
import io.github.lobofoltran.outbox.jdbc.internal.HeadersJsonWriter;
import io.github.lobofoltran.outbox.jdbc.internal.UuidV7Generator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JDBC implementation of {@link Outbox}. Writes one row per {@link OutboxEvent} to the configured
 * outbox table within the caller's active transaction.
 *
 * <p>The class is thread-safe; the precomputed SQL is immutable and every {@link #publish publish}
 * call uses a fresh {@link PreparedStatement} obtained from a caller-supplied {@link Connection}.
 * {@code JdbcOutbox} never closes the connection: that is the caller's responsibility (Spring's
 * transaction manager, the caller's try-with-resources block, etc.).
 *
 * <p>Construct via {@link #builder()}.
 */
public final class JdbcOutbox implements Outbox {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcOutbox.class);

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final ConnectionSupplier connectionSupplier;
    private final Clock clock;
    private final IdGenerator idGenerator;
    private final String insertSql;

    private JdbcOutbox(Builder builder) {
        this.connectionSupplier = builder.connectionSupplier;
        this.clock = builder.clock;
        this.idGenerator = builder.idGenerator;
        this.insertSql = buildInsertSql(builder.schema, builder.tableName);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public void publish(OutboxEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        UUID id = event.id() != null ? event.id() : idGenerator.generate(clock);
        String headers = HeadersJsonWriter.toJson(event.headers());

        try {
            Connection connection = connectionSupplier.get();
            try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
                statement.setObject(1, id);
                statement.setString(2, event.aggregateType());
                statement.setString(3, event.aggregateId());
                statement.setString(4, event.eventType());
                statement.setBytes(5, event.payload());
                statement.setString(6, event.contentType());
                statement.setString(7, headers);
                if (event.destination() != null) {
                    statement.setString(8, event.destination());
                } else {
                    statement.setNull(8, Types.VARCHAR);
                }
                statement.setTimestamp(9, Timestamp.from(event.occurredAt()));
                statement.executeUpdate();
            }
            LOG.debug(
                    "Persisted outbox event id={} aggregateType={} eventType={}",
                    id,
                    event.aggregateType(),
                    event.eventType());
        } catch (SQLException ex) {
            throw new OutboxException("failed to insert outbox event id=" + id, ex);
        }
    }

    private static String buildInsertSql(String schema, String tableName) {
        String qualified = schema != null ? schema + "." + tableName : tableName;
        return "INSERT INTO "
                + qualified
                + " ("
                + "id, aggregate_type, aggregate_id, event_type, payload, "
                + "content_type, headers, destination, occurred_at"
                + ") VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)";
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
     * </ul>
     *
     * Only {@link #connectionSupplier(ConnectionSupplier)} is required.
     *
     * <p>Instances are not thread-safe.
     */
    public static final class Builder {

        private ConnectionSupplier connectionSupplier;
        private String tableName = "outbox";
        private String schema;
        private Clock clock = Clock.systemUTC();
        private IdGenerator idGenerator = new UuidV7Generator();

        private Builder() {}

        public Builder connectionSupplier(ConnectionSupplier newConnectionSupplier) {
            this.connectionSupplier =
                    Objects.requireNonNull(
                            newConnectionSupplier, "connectionSupplier must not be null");
            return this;
        }

        public Builder tableName(String newTableName) {
            this.tableName = Objects.requireNonNull(newTableName, "tableName must not be null");
            return this;
        }

        public Builder schema(String newSchema) {
            this.schema = newSchema;
            return this;
        }

        public Builder clock(Clock newClock) {
            this.clock = Objects.requireNonNull(newClock, "clock must not be null");
            return this;
        }

        public Builder idGenerator(IdGenerator newIdGenerator) {
            this.idGenerator =
                    Objects.requireNonNull(newIdGenerator, "idGenerator must not be null");
            return this;
        }

        public JdbcOutbox build() {
            Objects.requireNonNull(connectionSupplier, "connectionSupplier must not be null");
            requireIdentifier(tableName, "tableName");
            if (schema != null) {
                requireIdentifier(schema, "schema");
            }
            return new JdbcOutbox(this);
        }

        private static void requireIdentifier(String value, String name) {
            if (!IDENTIFIER.matcher(value).matches()) {
                throw new IllegalArgumentException(
                        name + " must match " + IDENTIFIER.pattern() + " but was: " + value);
            }
        }
    }
}
