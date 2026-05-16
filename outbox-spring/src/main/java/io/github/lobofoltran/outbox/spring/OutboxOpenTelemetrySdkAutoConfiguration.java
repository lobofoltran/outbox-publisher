/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox.spring;

import java.util.HashMap;
import java.util.Map;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdkBuilder;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

/**
 * Bridges Spring's {@link Environment} into {@link AutoConfiguredOpenTelemetrySdk} so {@code
 * otel.*} keys placed in {@code application.yml} (or any other {@link PropertySource}) are honored
 * by the SDK.
 *
 * <p>Background — DEBT-12: the upstream {@code opentelemetry-sdk-extension-autoconfigure} consults
 * system properties, environment variables, and a classpath {@code .properties} file. It does
 * <em>not</em> read Spring's {@link Environment}, so an adopter who configures
 *
 * <pre>{@code
 * otel:
 *   service:
 *     name: my-service
 *   exporter:
 *     otlp:
 *       endpoint: http://collector:4317
 * }</pre>
 *
 * in {@code application.yml} sees every span tagged {@code service.name=unknown_service:java}.
 * There is no Spring-Boot-aware variant of the SDK autoconfigure in the official OpenTelemetry Java
 * repository; this autoconfig closes the gap with a minimal {@code PropertiesSupplier} bridge.
 *
 * <h2>When this autoconfig publishes a bean</h2>
 *
 * <ul>
 *   <li>{@code opentelemetry-sdk-extension-autoconfigure} is on the classpath ({@link
 *       AutoConfiguredOpenTelemetrySdk} resolves),
 *   <li>{@code io.github.lobofoltran.outbox.tracing.sdk.enabled} is explicitly set to {@code true}
 *       (opt-in; the bridge stays inert by default because {@code AutoConfiguredOpenTelemetrySdk}
 *       defaults every signal exporter to OTLP and would fail context startup if {@code
 *       opentelemetry-exporter-otlp} is absent from the classpath),
 *   <li>no other {@link OpenTelemetry} bean has been registered. Adopters wiring OpenTelemetry
 *       through the Java Agent or {@code opentelemetry-spring-boot-starter} are not overridden.
 * </ul>
 *
 * <p>The resulting {@link OpenTelemetry} bean is consumed by the tracing decorator in {@link
 * OutboxAutoConfiguration}. The bean factory declares {@code destroyMethod = "close"} so the SDK's
 * shutdown hook (flush exporters, close span processors) runs on context shutdown — the return type
 * is the {@link OpenTelemetry} interface to avoid a hard compile dependency on {@code
 * opentelemetry-sdk}, and Spring resolves the {@code close()} method reflectively against the
 * runtime instance.
 *
 * <h2>Global state</h2>
 *
 * <p>The SDK is <em>not</em> installed as the JVM-wide {@code GlobalOpenTelemetry} by default. The
 * outbox tracing decorator consumes the bean directly, and mutating the global can collide with the
 * OTel Java Agent. Adopters who need {@code GlobalOpenTelemetry.get()} (or downstream
 * instrumentation libraries that rely on it) to return this SDK should set {@code
 * io.github.lobofoltran.outbox.tracing.sdk.set-as-global=true}.
 *
 * @since 0.5.0
 */
@AutoConfiguration(before = OutboxAutoConfiguration.class)
@ConditionalOnClass({OpenTelemetry.class, AutoConfiguredOpenTelemetrySdk.class})
@ConditionalOnProperty(prefix = "io.github.lobofoltran.outbox.tracing.sdk", name = "enabled")
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxOpenTelemetrySdkAutoConfiguration {

    static final String OTEL_PROPERTY_PREFIX = "otel.";

    /**
     * Builds an {@link OpenTelemetrySdk} via {@link AutoConfiguredOpenTelemetrySdk}, seeded with
     * every {@code otel.*} property visible to Spring's {@link Environment}.
     *
     * <p>Spring properties act as <em>defaults</em>: the SDK's normal precedence (system properties
     * &gt; env vars &gt; properties supplier) is preserved, so a CLI flag or container env var
     * still wins over a value baked into {@code application.yml}.
     *
     * @param environment Spring's environment, scanned for {@code otel.*} keys.
     * @param properties bound outbox properties; only the {@code tracing.sdk} sub-record is read.
     * @return the configured SDK bean.
     * @since 0.5.0
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(OpenTelemetry.class)
    public OpenTelemetry outboxOpenTelemetrySdk(
            Environment environment, OutboxProperties properties) {
        Map<String, String> overrides = collectOtelProperties(environment);
        AutoConfiguredOpenTelemetrySdkBuilder builder =
                AutoConfiguredOpenTelemetrySdk.builder().addPropertiesSupplier(() -> overrides);
        if (properties.tracing().sdk().setAsGlobal()) {
            builder.setResultAsGlobal();
        }
        return builder.build().getOpenTelemetrySdk();
    }

    /**
     * Walks every {@link EnumerablePropertySource} in the environment and returns the {@code
     * otel.*} keys, resolving each through {@link Environment#getProperty(String)} so placeholders
     * are expanded.
     *
     * <p>Iteration follows the {@link ConfigurableEnvironment} precedence (highest-priority source
     * first); the first non-null value wins. Non-enumerable sources — e.g. the JNDI source — are
     * skipped because they cannot be scanned by prefix; adopters relying on those should set the
     * {@code otel.*} keys directly as system properties or environment variables, which the SDK
     * picks up natively.
     */
    static Map<String, String> collectOtelProperties(Environment environment) {
        Map<String, String> result = new HashMap<>();
        if (environment instanceof ConfigurableEnvironment configurable) {
            for (PropertySource<?> source : configurable.getPropertySources()) {
                if (source instanceof EnumerablePropertySource<?> enumerable) {
                    for (String name : enumerable.getPropertyNames()) {
                        if (name.startsWith(OTEL_PROPERTY_PREFIX) && !result.containsKey(name)) {
                            String value = environment.getProperty(name);
                            if (value != null) {
                                result.put(name, value);
                            }
                        }
                    }
                }
            }
        }
        return result;
    }
}
