/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * Sentinel test for the SQL contract shipped by {@code outbox-schema}. Catches accidental renames
 * or removals of the resource paths that downstream consumers depend on, and asserts the publisher
 * vs. relay-extension split documented in ADR-0007.
 */
class SchemaResourceTest {

    private static final String PUBLISHER_RESOURCE = "/sql/postgres/outbox-publisher.sql";
    private static final String RELAY_EXTENSION_RESOURCE =
            "/sql/postgres/outbox-relay-extension.sql";

    @Test
    void publisher_ddl_is_on_the_classpath_and_contains_only_publisher_columns()
            throws IOException {
        String content = readResource(PUBLISHER_RESOURCE);

        assertThat(content)
                .contains("CREATE TABLE outbox")
                .contains("id              UUID         PRIMARY KEY")
                .contains("aggregate_type")
                .contains("aggregate_id")
                .contains("event_type")
                .contains("payload         BYTEA")
                .contains("content_type")
                .contains("headers         JSONB")
                .contains("destination")
                .contains("occurred_at     TIMESTAMPTZ")
                .contains("schema_version  SMALLINT");

        assertThat(content)
                .as("publisher script must not carry any relay-side column or index")
                .doesNotContain("status")
                .doesNotContain("attempts")
                .doesNotContain("next_attempt_at")
                .doesNotContain("published_at")
                .doesNotContain("last_error")
                .doesNotContain("idx_outbox_pending")
                .doesNotContain("idx_outbox_sent");
    }

    @Test
    void relay_extension_ddl_is_on_the_classpath_and_contains_relay_columns_and_indexes()
            throws IOException {
        String content = readResource(RELAY_EXTENSION_RESOURCE);

        assertThat(content)
                .contains("ALTER TABLE outbox")
                .contains("ADD COLUMN IF NOT EXISTS status")
                .contains("ADD COLUMN IF NOT EXISTS attempts")
                .contains("ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMPTZ")
                .contains("ADD COLUMN IF NOT EXISTS published_at    TIMESTAMPTZ")
                .contains("ADD COLUMN IF NOT EXISTS last_error")
                .contains("CREATE INDEX IF NOT EXISTS idx_outbox_pending")
                .contains("CREATE INDEX IF NOT EXISTS idx_outbox_sent");
    }

    private static String readResource(String resource) throws IOException {
        try (InputStream in = SchemaResourceTest.class.getResourceAsStream(resource)) {
            assertThat(in)
                    .as("classpath resource %s must be packaged with outbox-schema", resource)
                    .isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
