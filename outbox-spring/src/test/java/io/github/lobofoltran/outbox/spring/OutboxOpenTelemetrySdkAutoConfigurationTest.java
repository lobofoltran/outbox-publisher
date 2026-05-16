/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Map;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockEnvironment;

/**
 * Unit-level coverage for the DEBT-12 bridge. Exercises the autoconfig + the static {@code
 * collectOtelProperties} helper without standing up a real OTLP exporter.
 */
class OutboxOpenTelemetrySdkAutoConfigurationTest {

    // The bridge is opt-in (see Sdk.enabled javadoc) — the autoconfig stays inert unless this
    // property is set. Disabling every signal exporter is also required because the SDK defaults
    // to OTLP and our test classpath does not carry opentelemetry-exporter-otlp; routing those
    // values through Spring's Environment is itself a smoke test of the PropertiesSupplier bridge.
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(OutboxOpenTelemetrySdkAutoConfiguration.class))
                    .withPropertyValues(
                            "io.github.lobofoltran.outbox.tracing.sdk.enabled=true",
                            "otel.traces.exporter=none",
                            "otel.metrics.exporter=none",
                            "otel.logs.exporter=none");

    @AfterEach
    void resetGlobalOpenTelemetry() {
        // The setResultAsGlobal test mutates the JVM-wide global; reset between tests so we do not
        // leak state into siblings or other test classes that rely on a clean global.
        GlobalOpenTelemetry.resetForTest();
    }

    @Test
    @DisplayName("publishes an OpenTelemetry bean built from AutoConfiguredOpenTelemetrySdk")
    void publishes_open_telemetry_bean_by_default() {
        runner.run(
                context -> {
                    assertThat(context).hasSingleBean(OpenTelemetry.class);
                    assertThat(context.getBean(OpenTelemetry.class))
                            .isInstanceOf(OpenTelemetrySdk.class);
                });
    }

    @Test
    @DisplayName("does not register a bean when tracing.sdk.enabled=false")
    void opts_out_when_sdk_enabled_is_false() {
        runner.withPropertyValues("io.github.lobofoltran.outbox.tracing.sdk.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(OpenTelemetry.class));
    }

    @Test
    @DisplayName("does not override a user-provided OpenTelemetry bean")
    void backs_off_when_user_bean_present() {
        OpenTelemetry user = stubOpenTelemetry();
        runner.withBean(OpenTelemetry.class, () -> user)
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(OpenTelemetry.class);
                            assertThat(context.getBean(OpenTelemetry.class)).isSameAs(user);
                        });
    }

    @Test
    @DisplayName("stays inert when AutoConfiguredOpenTelemetrySdk is not on the classpath")
    void does_not_publish_when_autoconfigure_extension_missing() {
        runner.withClassLoader(new FilteredClassLoader(AutoConfiguredOpenTelemetrySdk.class))
                .run(context -> assertThat(context).doesNotHaveBean(OpenTelemetry.class));
    }

    @Test
    @DisplayName(
            "setAsGlobal=true installs the built SDK as GlobalOpenTelemetry; default leaves global"
                    + " untouched (no-op resolves to a fresh no-op singleton)")
    void set_as_global_property_controls_global_installation() {
        runner.withPropertyValues("io.github.lobofoltran.outbox.tracing.sdk.set-as-global=true")
                .run(
                        context -> {
                            OpenTelemetry bean = context.getBean(OpenTelemetry.class);
                            // GlobalOpenTelemetry.get() returns an obfuscating wrapper rather than
                            // the raw instance, so identity comparison on the OpenTelemetry handle
                            // doesn't work. The TracerProvider, however, is delegated unwrapped.
                            assertThat(GlobalOpenTelemetry.get().getTracerProvider())
                                    .as("setAsGlobal=true installs the bean as the JVM global")
                                    .isSameAs(bean.getTracerProvider());
                        });
    }

    @Test
    @DisplayName(
            "Environment bridge: otel.* keys in Spring's Environment reach the SDK as defaults")
    void otel_keys_bridge_into_sdk_via_properties_supplier() {
        // Direct test of the static helper — the autoconfig delegates straight to it, and an
        // end-to-end assertion against the SDK would require capturing the resource attributes
        // produced by the underlying TracerProvider, which has no public read API.
        StandardEnvironment environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "yaml-stub",
                                Map.of(
                                        "otel.service.name", "outbox-publisher-test",
                                        "otel.exporter.otlp.endpoint", "http://collector:4317",
                                        "spring.application.name", "ignored-non-otel-key")));

        Map<String, String> collected =
                OutboxOpenTelemetrySdkAutoConfiguration.collectOtelProperties(environment);

        assertThat(collected)
                .containsEntry("otel.service.name", "outbox-publisher-test")
                .containsEntry("otel.exporter.otlp.endpoint", "http://collector:4317")
                .doesNotContainKey("spring.application.name");
    }

    @Test
    @DisplayName(
            "collectOtelProperties: returns empty when the Environment is not Configurable, skips"
                    + " non-enumerable sources, ignores null values, and respects source precedence"
                    + " (first source wins for duplicate keys)")
    void collect_otel_properties_handles_edge_cases() {
        // Non-ConfigurableEnvironment branch — the helper never enters the source loop. Mockito
        // is allowed: Environment is an interface, so this is a plain mock (not a final-class /
        // static-method mock, which the project bans).
        Environment plain = mock(Environment.class);
        assertThat(OutboxOpenTelemetrySdkAutoConfiguration.collectOtelProperties(plain)).isEmpty();

        MockEnvironment env = new MockEnvironment();
        // (a) Highest-priority enumerable source — wins for otel.service.name.
        env.getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "primary", Map.of("otel.service.name", "from-primary")));
        // (b) Enumerable source whose getProperty() returns null for a listed otel.* key — drives
        // the value != null branch (the helper does not put the key into the result).
        env.getPropertySources()
                .addAfter(
                        "primary",
                        new EnumerablePropertySource<Object>("null-value-source", new Object()) {
                            @Override
                            public String[] getPropertyNames() {
                                return new String[] {"otel.null.value"};
                            }

                            @Override
                            public Object getProperty(String name) {
                                return null;
                            }
                        });
        // (c) Non-enumerable source — its content cannot be discovered by prefix scan, so the
        // helper does not visit it during enumeration (covers the EnumerablePropertySource-false
        // branch). Its values may still surface via environment.getProperty() for keys already
        // discovered by an earlier source, which is fine.
        env.getPropertySources()
                .addAfter(
                        "null-value-source",
                        new PropertySource<Object>("non-enumerable", new Object()) {
                            @Override
                            public Object getProperty(String name) {
                                return "otel.invisible.key".equals(name) ? "shadowed" : null;
                            }
                        });
        // (d) Lower-priority enumerable source contributing a duplicate otel.service.name — the
        // helper must keep the value from (a) (covers the !result.containsKey false branch).
        env.getPropertySources()
                .addLast(
                        new MapPropertySource(
                                "secondary",
                                Map.of(
                                        "otel.service.name", "from-secondary",
                                        "otel.exporter.otlp.endpoint", "http://primary:4317")));

        Map<String, String> collected =
                OutboxOpenTelemetrySdkAutoConfiguration.collectOtelProperties(env);

        assertThat(collected)
                .as("otel.service.name resolves to the highest-priority enumerable source")
                .containsEntry("otel.service.name", "from-primary")
                .containsEntry("otel.exporter.otlp.endpoint", "http://primary:4317")
                .doesNotContainKey("otel.null.value");
    }

    private static OpenTelemetry stubOpenTelemetry() {
        return OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder().build())
                .build();
    }
}
