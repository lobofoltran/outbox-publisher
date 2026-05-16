package io.github.lobofoltran.outbox.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * Sentinel test for the SQL contract shipped by {@code outbox-schema}. Catches accidental renames
 * or removals of the resource path that downstream consumers depend on.
 */
class SchemaResourceTest {

    private static final String DDL_RESOURCE = "/sql/postgres/outbox.sql";

    @Test
    void postgres_ddl_is_on_the_classpath() throws IOException {
        try (InputStream in = SchemaResourceTest.class.getResourceAsStream(DDL_RESOURCE)) {
            assertThat(in)
                    .as("classpath resource %s must be packaged with outbox-schema", DDL_RESOURCE)
                    .isNotNull();
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(content)
                    .contains("CREATE TABLE outbox")
                    .contains("CREATE INDEX idx_outbox_pending")
                    .contains("CREATE INDEX idx_outbox_sent")
                    .contains("schema_version  SMALLINT");
        }
    }
}
