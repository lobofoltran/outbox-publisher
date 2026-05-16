package io.github.lobofoltran.outbox.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.Test;

/**
 * Asserts that {@code JdbcOutbox.class} contains no references to symbols inside the internal
 * {@code io.github.lobofoltran.outbox.jdbc.dialect} package, nor any PostgreSQL-specific tokens.
 * This codifies the rule that {@code JdbcOutbox} must remain database-agnostic and only talk to the
 * {@code OutboxDialect} SPI.
 *
 * <p>Implementation note: we scan the raw class file bytes for marker strings rather than using
 * ASM. The constant pool contains internal-form package paths ({@code
 * io/github/lobofoltran/outbox/jdbc/dialect}) and any string literal verbatim, so a substring
 * search over the whole file catches both class references and inadvertent SQL fragments.
 */
class JdbcOutboxBytecodePurityTest {

    @Test
    void jdbc_outbox_class_does_not_reference_dialect_internal_package() throws IOException {
        String classBytes = readClassAsLatin1(JdbcOutbox.class);
        assertThat(classBytes)
                .as("JdbcOutbox must not reference classes in the internal dialect package")
                .doesNotContain("io/github/lobofoltran/outbox/jdbc/dialect");
    }

    @Test
    void jdbc_outbox_class_contains_no_postgres_specific_tokens() throws IOException {
        String classBytes = readClassAsLatin1(JdbcOutbox.class);
        assertThat(classBytes)
                .as("JdbcOutbox must not embed PostgreSQL-specific SQL fragments")
                .doesNotContain("jsonb")
                .doesNotContain("Postgres")
                .doesNotContain("postgres")
                .doesNotContain("ON CONFLICT");
    }

    private static String readClassAsLatin1(Class<?> type) throws IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("class file not on classpath: " + resource);
            }
            // ISO-8859-1 is a 1:1 byte-to-char mapping, so substring searches work over raw
            // bytes even though the constant pool encodes UTF-8 — ASCII tokens are identical.
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
        }
    }
}
