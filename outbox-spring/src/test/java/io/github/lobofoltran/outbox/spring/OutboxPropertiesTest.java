/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox.spring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OutboxPropertiesTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(BindingHarness.class);

    @Test
    @DisplayName("defaults: enabled=true, table-name=outbox, all sub-features enabled")
    void defaults_are_applied() {
        runner.run(
                context -> {
                    OutboxProperties props = context.getBean(OutboxProperties.class);
                    assertThat(props.enabled()).isTrue();
                    assertThat(props.tableName()).isEqualTo("outbox");
                    assertThat(props.schema()).isNull();
                    assertThat(props.metrics().enabled()).isTrue();
                    assertThat(props.tracing().enabled()).isTrue();
                    assertThat(props.health().enabled()).isTrue();
                });
    }

    @Test
    @DisplayName("nested keys metrics.enabled / tracing.enabled / health.enabled bind correctly")
    void nested_keys_bind() {
        runner.withPropertyValues(
                        "io.github.lobofoltran.outbox.metrics.enabled=false",
                        "io.github.lobofoltran.outbox.tracing.enabled=false",
                        "io.github.lobofoltran.outbox.health.enabled=false")
                .run(
                        context -> {
                            OutboxProperties props = context.getBean(OutboxProperties.class);
                            assertThat(props.metrics().enabled()).isFalse();
                            assertThat(props.tracing().enabled()).isFalse();
                            assertThat(props.health().enabled()).isFalse();
                        });
    }

    @EnableConfigurationProperties(OutboxProperties.class)
    static class BindingHarness {}
}
