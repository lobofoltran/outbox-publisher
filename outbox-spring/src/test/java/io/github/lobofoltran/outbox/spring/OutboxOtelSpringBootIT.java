/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;

import io.github.lobofoltran.outbox.Outbox;
import io.github.lobofoltran.outbox.OutboxEvent;
import io.github.lobofoltran.outbox.otel.TracedOutbox;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class OutboxOtelSpringBootIT {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration.class))
                    .withBean(DataSource.class, () -> mock(DataSource.class));

    @Test
    @DisplayName("wraps the Outbox bean with TracedOutbox when an OpenTelemetry bean is present")
    void wraps_with_traced_outbox_when_open_telemetry_is_available() {
        runner.withBean(OpenTelemetry.class, OutboxOtelSpringBootIT::stubOpenTelemetry)
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(Outbox.class);
                            assertThat(context.getBean(Outbox.class))
                                    .isInstanceOf(TracedOutbox.class);
                        });
    }

    @AfterEach
    void resetGlobalOpenTelemetry() {
        // Each test owns its OpenTelemetry resolution path; clear any global set during the test
        // so we don't leak state across test methods or test classes.
        GlobalOpenTelemetry.resetForTest();
    }

    @Test
    @DisplayName(
            "falls back to GlobalOpenTelemetry when no OpenTelemetry bean is registered"
                    + " (covers the opentelemetry-sdk-extension-autoconfigure / Java Agent path)")
    void falls_back_to_global_open_telemetry_when_no_bean() {
        GlobalOpenTelemetry.set(stubOpenTelemetry());
        runner.run(
                context -> {
                    assertThat(context).hasSingleBean(Outbox.class);
                    assertThat(context.getBean(Outbox.class)).isInstanceOf(TracedOutbox.class);
                });
    }

    @Test
    @DisplayName(
            "still wraps with TracedOutbox when no bean and no global SDK are present"
                    + " (GlobalOpenTelemetry.get() returns a no-op implementation)")
    void wraps_with_noop_when_no_bean_and_no_global() {
        // No OpenTelemetry bean, GlobalOpenTelemetry is reset (see @AfterEach contract): the
        // decorator is still applied but emits no spans. This is the documented contract — the
        // application opts out via tracing.enabled=false, not by withholding the bean.
        runner.run(
                context -> {
                    assertThat(context).hasSingleBean(Outbox.class);
                    assertThat(context.getBean(Outbox.class)).isInstanceOf(TracedOutbox.class);
                });
    }

    @Test
    @DisplayName("respects tracing.enabled=false even when OpenTelemetry is available")
    void opts_out_when_tracing_enabled_is_false() {
        runner.withBean(OpenTelemetry.class, OutboxOtelSpringBootIT::stubOpenTelemetry)
                .withPropertyValues("io.github.lobofoltran.outbox.tracing.enabled=false")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(Outbox.class);
                            assertThat(context.getBean(Outbox.class))
                                    .isNotInstanceOf(TracedOutbox.class);
                        });
    }

    @Test
    @DisplayName("does not double-wrap a user-provided TracedOutbox")
    void does_not_double_wrap_existing_traced_outbox() {
        runner.withBean(OpenTelemetry.class, OutboxOtelSpringBootIT::stubOpenTelemetry)
                .withUserConfiguration(UserTracedOutboxConfig.class)
                .run(
                        context -> {
                            Outbox bean = context.getBean(Outbox.class);
                            assertThat(bean).isInstanceOf(TracedOutbox.class);
                            assertThat(bean)
                                    .isSameAs(
                                            context.getBean(UserTracedOutboxConfig.class).expected);
                        });
    }

    @Test
    @DisplayName("leaves the Outbox unwrapped when TracedOutbox is not on the classpath")
    void does_not_wrap_when_outbox_otel_is_missing() {
        runner.withBean(OpenTelemetry.class, OutboxOtelSpringBootIT::stubOpenTelemetry)
                .withClassLoader(new FilteredClassLoader(TracedOutbox.class))
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(Outbox.class);
                            assertThat(context.getBean(Outbox.class))
                                    .isNotInstanceOf(TracedOutbox.class);
                        });
    }

    private static OpenTelemetry stubOpenTelemetry() {
        return OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder().build())
                .build();
    }

    @Configuration
    static class UserTracedOutboxConfig {

        final TracedOutbox expected;

        UserTracedOutboxConfig() {
            Tracer tracer = stubOpenTelemetry().getTracer("test");
            this.expected = new TracedOutbox(new StubOutbox(), tracer);
        }

        @Bean
        Outbox outbox() {
            return expected;
        }
    }

    static class StubOutbox implements Outbox {

        @Override
        public void publish(OutboxEvent event) {
            // no-op stub for the user-bean test
        }
    }
}
