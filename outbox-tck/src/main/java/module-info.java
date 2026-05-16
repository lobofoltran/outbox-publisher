/**
 * Technology Compatibility Kit (TCK) for {@link
 * io.github.lobofoltran.outbox.jdbc.spi.OutboxDialect} implementations.
 *
 * <p>Ships a single abstract JUnit 5 base class — {@link
 * io.github.lobofoltran.outbox.tck.OutboxDialectContractTest} — that pins the runtime contract
 * every dialect must satisfy. External dialect authors (e.g. a hypothetical {@code
 * outbox-jdbc-mysql}) extend it as a regular {@code test}-scope dependency, wire their dialect plus
 * a {@link javax.sql.DataSource}, and inherit the full contract suite.
 *
 * <p>The contract base lives in {@code src/main/java} on purpose so it ships in the main JAR (no
 * {@code classifier=tests}). See ADR-0016.
 */
module io.github.lobofoltran.outbox.tck {
    requires transitive io.github.lobofoltran.outbox.core;
    requires transitive io.github.lobofoltran.outbox.jdbc;
    requires transitive java.sql;
    // Required for `javax.sql.DataSource` implementations on the consumer's classpath that
    // implement `javax.naming.Referenceable` (e.g. PostgreSQL's `PGSimpleDataSource`); without
    // this directive `javac` fails type-checking the subclass with `cannot access
    // javax.naming.Referenceable`.
    requires java.naming;
    requires transitive org.junit.jupiter.api;
    requires transitive org.assertj.core;

    // Testcontainers is a Maven-scope convenience for subclassers (carried transitively by the
    // BOM); intentionally NOT a JPMS `requires` because the TCK's own source does not reference
    // any Testcontainers type in its public API. Subclassers' own modules declare it themselves.

    exports io.github.lobofoltran.outbox.tck;
}
