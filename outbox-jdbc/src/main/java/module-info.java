/**
 * JDBC-backed implementation of {@link io.github.lobofoltran.outbox.Outbox}. The single exported
 * package contains {@link io.github.lobofoltran.outbox.jdbc.JdbcOutbox} and its supporting
 * functional interfaces. Mapping internals live in the unexported {@code
 * io.github.lobofoltran.outbox.jdbc.internal} package.
 */
module io.github.lobofoltran.outbox.jdbc {
    // Transitive so that consumers requiring outbox-jdbc can also use the Outbox
    // interface and OutboxEvent record without re-declaring the core requires.
    requires transitive io.github.lobofoltran.outbox.core;
    requires java.sql;
    requires org.slf4j;

    exports io.github.lobofoltran.outbox.jdbc;
}
