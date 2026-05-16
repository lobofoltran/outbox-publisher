/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Smoke test that asserts the Spring configuration metadata produced by {@code
 * spring-boot-configuration-processor} is on the test classpath and exposes every {@link
 * OutboxProperties} key — flat and nested. Catches regressions where someone removes the
 * configuration-processor dependency or breaks {@code -proc:full}.
 */
class SpringConfigurationMetadataTest {

    private static final String RESOURCE = "/META-INF/spring-configuration-metadata.json";

    @Test
    @DisplayName("metadata file is generated and exposes every documented property key")
    void metadata_is_present_and_complete() throws Exception {
        String json = readClasspathResource(RESOURCE);
        assertThat(json)
                .as("spring-configuration-metadata.json must be on the classpath")
                .isNotBlank()
                .contains("\"name\": \"io.github.lobofoltran.outbox.enabled\"")
                .contains("\"name\": \"io.github.lobofoltran.outbox.table-name\"")
                .contains("\"name\": \"io.github.lobofoltran.outbox.metrics.enabled\"")
                .contains("\"name\": \"io.github.lobofoltran.outbox.metrics.tag-fallback\"")
                .contains("\"name\": \"io.github.lobofoltran.outbox.metrics.event-type-allowlist\"")
                .contains("\"name\": \"io.github.lobofoltran.outbox.tracing.enabled\"")
                .contains("\"name\": \"io.github.lobofoltran.outbox.health.enabled\"");
    }

    private static String readClasspathResource(String resource) throws Exception {
        try (InputStream in = SpringConfigurationMetadataTest.class.getResourceAsStream(resource)) {
            assertThat(in).as("classpath resource %s", resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
