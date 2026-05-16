/**
 * JDBC-backed implementation of {@link io.github.lobofoltran.outbox.Outbox}. Two packages are
 * exported: {@code io.github.lobofoltran.outbox.jdbc} contains {@link
 * io.github.lobofoltran.outbox.jdbc.JdbcOutbox} and its supporting functional interfaces; {@code
 * io.github.lobofoltran.outbox.jdbc.spi} contains the {@link
 * io.github.lobofoltran.outbox.jdbc.spi.OutboxDialect} SPI for plugging in non-PostgreSQL
 * databases. Mapping internals (header serialization, UUID generation) and the bundled PostgreSQL
 * dialect implementation live in non-exported packages.
 */
module io.github.lobofoltran.outbox.jdbc {
    // Transitive so that consumers requiring outbox-jdbc can also use the Outbox
    // interface and OutboxEvent record without re-declaring the core requires.
    requires transitive io.github.lobofoltran.outbox.core;
    // java.sql is transitive because ConnectionSupplier and the OutboxDialect SPI both expose
    // java.sql types in their public signatures.
    requires transitive java.sql;
    requires org.slf4j;

    exports io.github.lobofoltran.outbox.jdbc;
    exports io.github.lobofoltran.outbox.jdbc.spi;

    uses io.github.lobofoltran.outbox.jdbc.spi.OutboxDialectProvider;

    provides io.github.lobofoltran.outbox.jdbc.spi.OutboxDialectProvider with
            io.github.lobofoltran.outbox.jdbc.dialect.postgres.PostgresDialectProvider;
}
