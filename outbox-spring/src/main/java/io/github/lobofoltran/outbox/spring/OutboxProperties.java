package io.github.lobofoltran.outbox.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for {@link OutboxAutoConfiguration}. Bound to the {@code
 * io.github.lobofoltran.outbox} property prefix.
 *
 * @param enabled master switch. When {@code false} the autoconfig publishes no beans.
 * @param tableName name of the outbox table. Default {@code outbox}.
 * @param schema optional schema qualifier. When {@code null} or blank the table is referenced
 *     unqualified.
 */
@ConfigurationProperties(prefix = "io.github.lobofoltran.outbox")
public record OutboxProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("outbox") String tableName,
        String schema) {}
