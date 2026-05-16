package io.github.lobofoltran.outbox.spring;

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
     */
    public record Metrics(@DefaultValue("true") boolean enabled) {}

    /**
     * Tracing decorator settings. Bound under {@code io.github.lobofoltran.outbox.tracing}.
     *
     * @param enabled when {@code false}, the {@code TracedOutbox} decorator is not applied even if
     *     OpenTelemetry is on the classpath and an {@code OpenTelemetry} bean exists.
     */
    public record Tracing(@DefaultValue("true") boolean enabled) {}

    /**
     * Health indicator settings. Bound under {@code io.github.lobofoltran.outbox.health}.
     *
     * @param enabled when {@code false}, the {@code OutboxHealthIndicator} is not registered even
     *     if Spring Boot Actuator is on the classpath.
     */
    public record Health(@DefaultValue("true") boolean enabled) {}
}
