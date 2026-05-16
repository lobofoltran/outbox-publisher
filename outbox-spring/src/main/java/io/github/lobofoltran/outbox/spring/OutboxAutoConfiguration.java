package io.github.lobofoltran.outbox.spring;

import javax.sql.DataSource;

import io.github.lobofoltran.outbox.Outbox;
import io.github.lobofoltran.outbox.jdbc.JdbcOutbox;
import io.github.lobofoltran.outbox.micrometer.MeteredOutbox;
import io.github.lobofoltran.outbox.otel.TracedOutbox;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceUtils;

/**
 * Auto-configures an {@link Outbox} bean backed by {@link JdbcOutbox}.
 *
 * <p>The bean is published only when:
 *
 * <ul>
 *   <li>{@code outbox-jdbc} is on the classpath (so {@link JdbcOutbox} resolves),
 *   <li>a single {@link DataSource} bean exists in the context,
 *   <li>the property {@code io.github.lobofoltran.outbox.enabled} is missing or {@code true},
 *   <li>no other {@link Outbox} bean was already registered by the user.
 * </ul>
 *
 * <p>The {@code connectionSupplier} delegates to {@link DataSourceUtils#getConnection
 * DataSourceUtils.getConnection(dataSource)} so the INSERT participates in whichever transaction
 * Spring has bound to the current thread.
 *
 * <p>When both Micrometer and {@link MeteredOutbox} are on the classpath and a {@link
 * MeterRegistry} bean exists, every {@link Outbox} bean in the context is wrapped with {@link
 * MeteredOutbox} via a {@link BeanPostProcessor}. The wrapping is opt-out through {@code
 * io.github.lobofoltran.outbox.metrics.enabled=false}.
 *
 * <p>When OpenTelemetry and {@link TracedOutbox} are on the classpath and an {@link OpenTelemetry}
 * bean exists, every {@link Outbox} bean in the context is wrapped with {@link TracedOutbox} via a
 * {@link BeanPostProcessor}. The wrapping is opt-out through {@code
 * io.github.lobofoltran.outbox.tracing.enabled=false}.
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@ConditionalOnClass({Outbox.class, JdbcOutbox.class})
@ConditionalOnSingleCandidate(DataSource.class)
@ConditionalOnProperty(
        prefix = "io.github.lobofoltran.outbox",
        name = "enabled",
        matchIfMissing = true)
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(Outbox.class)
    public Outbox outbox(DataSource dataSource, OutboxProperties properties) {
        JdbcOutbox.Builder builder =
                JdbcOutbox.builder()
                        .connectionSupplier(() -> DataSourceUtils.getConnection(dataSource))
                        .tableName(properties.tableName());
        String schema = properties.schema();
        if (schema != null && !schema.isBlank()) {
            builder.schema(schema);
        }
        return builder.build();
    }

    /**
     * Wraps every {@link Outbox} bean in the context with a {@link MeteredOutbox} when Micrometer
     * is on the classpath and a {@link MeterRegistry} is available.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({MeterRegistry.class, MeteredOutbox.class})
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnProperty(
            prefix = "io.github.lobofoltran.outbox.metrics",
            name = "enabled",
            matchIfMissing = true)
    static class MetricsConfiguration {

        @Bean
        static BeanPostProcessor outboxMetricsBeanPostProcessor(
                ObjectProvider<MeterRegistry> registryProvider) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (bean instanceof Outbox outbox && !(bean instanceof MeteredOutbox)) {
                        return new MeteredOutbox(outbox, registryProvider.getObject());
                    }
                    return bean;
                }
            };
        }
    }

    /**
     * Wraps every {@link Outbox} bean in the context with a {@link TracedOutbox} when the
     * OpenTelemetry API is on the classpath and an {@link OpenTelemetry} bean is available.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({Tracer.class, TracedOutbox.class})
    @ConditionalOnBean(OpenTelemetry.class)
    @ConditionalOnProperty(
            prefix = "io.github.lobofoltran.outbox.tracing",
            name = "enabled",
            matchIfMissing = true)
    static class TracingConfiguration {

        static final String TRACER_INSTRUMENTATION_SCOPE_NAME = "io.github.lobofoltran.outbox";

        @Bean
        static BeanPostProcessor outboxTracingBeanPostProcessor(
                ObjectProvider<OpenTelemetry> openTelemetryProvider) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (bean instanceof Outbox outbox && !(bean instanceof TracedOutbox)) {
                        Tracer tracer =
                                openTelemetryProvider
                                        .getObject()
                                        .getTracer(TRACER_INSTRUMENTATION_SCOPE_NAME);
                        return new TracedOutbox(outbox, tracer);
                    }
                    return bean;
                }
            };
        }
    }
}
