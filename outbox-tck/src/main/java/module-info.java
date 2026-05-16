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
 * {@code classifier=tests}), making it consumable as a regular {@code test}-scope dependency.
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

    // Testcontainers is intentionally NOT a JPMS `requires` here. The TCK contract base
    // (`OutboxDialectContractTest`) accepts a `javax.sql.DataSource` — no Testcontainers type
    // appears in any of its method signatures or fields. Subclassers (e.g.
    // `PostgresDialectContractIT`)
    // wire Testcontainers themselves and declare `requires testcontainers` (or stay on the
    // classpath) at their level. Re-exporting it transitively from the TCK would pin a
    // module-name choice on every consumer (the JAR has no `Automatic-Module-Name`, so the name
    // is derived from the filename) and break in-reactor tests that use
    // `org.testcontainers:postgresql`
    // since requiring `testcontainers` switches the test compile to a strict module path.
    // Maven scope still carries the JAR transitively for convenience — see `outbox-tck/pom.xml`.

    exports io.github.lobofoltran.outbox.tck;
}
