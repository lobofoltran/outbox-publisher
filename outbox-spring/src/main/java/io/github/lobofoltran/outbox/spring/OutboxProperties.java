/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox.spring;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for {@link OutboxAutoConfiguration}. Bound to the {@code
 * io.github.lobofoltran.outbox} property prefix.
 *
 * <p>Sub-features (metrics, tracing, health) are exposed as nested records. Spring's relaxed
 * binding accepts both flat (e.g. {@code io.github.lobofoltran.outbox.metrics-enabled}) and nested
 * (e.g. {@code io.github.lobofoltran.outbox.metrics.enabled}) keys; the nested form documented here
 * is the canonical one and the one published in {@code spring-configuration-metadata.json}.
 *
 * @param enabled master switch. When {@code false} the autoconfig publishes no beans.
 * @param tableName name of the outbox table. Default {@code outbox}.
 * @param schema optional schema qualifier. When {@code null} or blank the table is referenced
 *     unqualified.
 * @param metrics metrics decorator settings. See {@link Metrics}.
 * @param tracing tracing decorator settings. See {@link Tracing}.
 * @param health health indicator settings. See {@link Health}.
 * @since 0.1.0
 */
@ConfigurationProperties(prefix = "io.github.lobofoltran.outbox")
public record OutboxProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("outbox") String tableName,
        String schema,
        @DefaultValue Metrics metrics,
        @DefaultValue Tracing tracing,
        @DefaultValue Health health) {

    /**
     * Metrics decorator settings. Bound under {@code io.github.lobofoltran.outbox.metrics}.
     *
     * @param enabled when {@code false}, the {@code MeteredOutbox} decorator is not applied even if
     *     Micrometer is on the classpath and a {@code MeterRegistry} bean exists.
     * @param tagFallback replacement string used when a tag value is rejected by the cardinality
     *     allowlist. Defaults to {@code "other"}.
     * @param eventTypeAllowlist closed set of permitted {@code event_type} tag values. When empty
     *     (default) all values pass through, preserving v0.1 behavior. When non-empty, values not
     *     in the list are recorded under {@link #tagFallback} instead, capping cardinality.
     * @since 0.1.0
     */
    public record Metrics(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("other") String tagFallback,
            @DefaultValue List<String> eventTypeAllowlist) {}

    /**
     * Tracing decorator settings. Bound under {@code io.github.lobofoltran.outbox.tracing}.
     *
     * @param enabled when {@code false}, the {@code TracedOutbox} decorator is not applied even if
     *     OpenTelemetry is on the classpath. When {@code true} (default), the decorator is applied
     *     whenever the OpenTelemetry API is on the classpath; the {@code OpenTelemetry} instance is
     *     resolved from a Spring bean if present, falling back to {@code GlobalOpenTelemetry.get()}
     *     (no-op when no SDK has been installed).
     * @param sdk OpenTelemetry SDK auto-configuration settings. See {@link Sdk}.
     * @since 0.1.0
     */
    public record Tracing(@DefaultValue("true") boolean enabled, @DefaultValue Sdk sdk) {

        /**
         * Spring-aware {@code opentelemetry-sdk-extension-autoconfigure} bridge. Bound under {@code
         * io.github.lobofoltran.outbox.tracing.sdk}.
         *
         * <p>The upstream {@code AutoConfiguredOpenTelemetrySdk} only consults system properties,
         * environment variables, and a properties file. It does not read Spring's {@code
         * Environment}, which means {@code otel.*} keys placed in {@code application.yml} are
         * silently ignored and every span ends up under {@code unknown_service:java}. When the
         * autoconfigure extension is on the classpath, this library bridges the gap by collecting
         * every {@code otel.*} key visible to Spring's {@code Environment} and passing them as a
         * {@code PropertiesSupplier} to the SDK builder. The resulting {@code OpenTelemetry}
         * instance is exposed as a Spring bean and is picked up by the tracing decorator.
         *
         * @param enabled opt-in switch. Defaults to {@code false} because {@code
         *     AutoConfiguredOpenTelemetrySdk} defaults every signal exporter to OTLP and would fail
         *     context startup if {@code opentelemetry-exporter-otlp} is absent. Adopters who want
         *     the Spring-aware bridge set this to {@code true} and supply {@code otel.*} keys in
         *     {@code application.yml}. Adopters wiring OpenTelemetry through the Java Agent or the
         *     {@code opentelemetry-spring-boot-starter} should leave this {@code false} to avoid
         *     building a second SDK.
         * @param setAsGlobal when {@code true}, the built SDK is installed as the JVM-wide {@code
         *     GlobalOpenTelemetry} instance. Defaults to {@code false} because the outbox tracing
         *     decorator consumes the bean directly and global mutation can collide with the OTel
         *     Java Agent.
         * @since 0.5.0
         */
        public record Sdk(
                @DefaultValue("false") boolean enabled,
                @DefaultValue("false") boolean setAsGlobal) {}
    }

    /**
     * Health indicator settings. Bound under {@code io.github.lobofoltran.outbox.health}.
     *
     * @param enabled when {@code false}, the {@code OutboxHealthIndicator} is not registered even
     *     if Spring Boot Actuator is on the classpath.
     * @since 0.1.0
     */
    public record Health(@DefaultValue("true") boolean enabled) {}
}
