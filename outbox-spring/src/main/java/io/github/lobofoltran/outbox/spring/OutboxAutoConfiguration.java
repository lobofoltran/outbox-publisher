/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox.spring;

import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
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
import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
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
 * <h2>Customization</h2>
 *
 * <p>Adopters can register one or more {@link JdbcOutboxBuilderCustomizer} beans to tweak the
 * auto-configured builder (custom {@code IdGenerator}, deterministic {@code Clock}, explicit {@code
 * OutboxDialect}, …) without having to hand-roll a replacement {@link Outbox} bean. Customizers are
 * invoked in {@code @Order}-aware sequence, after {@link OutboxProperties} have been applied to the
 * builder and immediately before {@code build()}.
 *
 * <h2>Decoration order</h2>
 *
 * <p>When both observability decorators are eligible, the resulting bean stack is:
 *
 * <pre>{@code
 * TracedOutbox(MeteredOutbox(JdbcOutbox))
 * }</pre>
 *
 * <p>This ordering is enforced by both {@link BeanPostProcessor} implementations directly
 * implementing {@link Ordered}. Spring runs ordered {@code BeanPostProcessor}s with the lowest
 * order value first, so the metrics BPP carries the lower value ({@link Ordered#LOWEST_PRECEDENCE}
 * {@code - 200}) and runs first, seeing the bare {@link JdbcOutbox}; the tracing BPP carries {@link
 * Ordered#LOWEST_PRECEDENCE} {@code - 100} and runs last, wrapping the already-metered delegate.
 * The effect is that the metric increments and the OpenTelemetry span share the same logical
 * operation: the metric is recorded inside the producer span. See ADR-0017.
 *
 * <p>Both decorators are opt-out via the nested {@link OutboxProperties.Metrics metrics.enabled}
 * and {@link OutboxProperties.Tracing tracing.enabled} switches.
 *
 * <h2>Health</h2>
 *
 * <p>If Spring Boot Actuator is on the classpath an {@link OutboxHealthIndicator} is registered
 * under the name {@code outbox}. It is opt-out via {@link OutboxProperties.Health health.enabled}
 * and respects the standard {@code management.health.outbox.enabled} switch.
 *
 * @since 0.1.0
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

    /**
     * Creates the default {@link Outbox} bean (a {@link JdbcOutbox}) wired to the single {@link
     * DataSource} in the context, with optional {@link JdbcOutboxBuilderCustomizer} hooks applied
     * before {@link JdbcOutbox.Builder#build()}.
     *
     * @param dataSource the {@link DataSource} the outbox writes through.
     * @param properties the bound {@link OutboxProperties}.
     * @param customizers ordered customizers contributed by the application.
     * @return the configured {@link Outbox} bean.
     * @since 0.1.0
     */
    @Bean
    @ConditionalOnMissingBean(Outbox.class)
    public Outbox outbox(
            DataSource dataSource,
            OutboxProperties properties,
            ObjectProvider<JdbcOutboxBuilderCustomizer> customizers) {
        JdbcOutbox.Builder builder =
                JdbcOutbox.builder()
                        .connectionSupplier(() -> DataSourceUtils.getConnection(dataSource))
                        .tableName(properties.tableName());
        String schema = properties.schema();
        if (schema != null && !schema.isBlank()) {
            builder.schema(schema);
        }
        customizers.orderedStream().forEach(c -> c.customize(builder));
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

        // Return the concrete BPP type (which implements Ordered) so Spring's pre-instantiation
        // type check in PostProcessorRegistrationDelegate#registerBeanPostProcessors recognizes
        // the BPP as Ordered and sorts it accordingly. Returning BeanPostProcessor here puts the
        // BPP in the "non-ordered" bucket, where @Order on the factory method is also ignored,
        // and the decoration order becomes implementation-defined (observed: tracing-then-metrics,
        // i.e. the wrong way round).
        @Bean
        static MetricsBeanPostProcessor outboxMetricsBeanPostProcessor(
                ObjectProvider<MeterRegistry> registryProvider,
                ObjectProvider<OutboxProperties> propertiesProvider) {
            return new MetricsBeanPostProcessor(registryProvider, propertiesProvider);
        }

        /**
         * Decorates {@link Outbox} beans with {@link MeteredOutbox}. Implements {@link Ordered}
         * with {@link Ordered#LOWEST_PRECEDENCE} {@code - 200} so it runs <em>before</em> the
         * tracing BPP (lower order values are invoked first), producing the inner {@code
         * MeteredOutbox} layer that tracing then wraps. See ADR-0017 and the class javadoc.
         *
         * <p>{@link Ordered} is implemented on the BPP class itself rather than via {@code @Order}
         * on the {@code @Bean} factory method because Spring's BPP registration sorts by {@link
         * Ordered}/{@link org.springframework.core.PriorityOrdered PriorityOrdered}; the
         * factory-method annotation is not enough on its own.
         */
        static final class MetricsBeanPostProcessor implements BeanPostProcessor, Ordered {

            private final ObjectProvider<MeterRegistry> registryProvider;
            private final ObjectProvider<OutboxProperties> propertiesProvider;

            MetricsBeanPostProcessor(
                    ObjectProvider<MeterRegistry> registryProvider,
                    ObjectProvider<OutboxProperties> propertiesProvider) {
                this.registryProvider = registryProvider;
                this.propertiesProvider = propertiesProvider;
            }

            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof Outbox outbox && !(bean instanceof MeteredOutbox)) {
                    OutboxProperties.Metrics metrics = propertiesProvider.getObject().metrics();
                    return new MeteredOutbox(
                            outbox,
                            registryProvider.getObject(),
                            buildPredicate(metrics.eventTypeAllowlist()),
                            metrics.tagFallback());
                }
                return bean;
            }

            @Override
            public int getOrder() {
                return Ordered.LOWEST_PRECEDENCE - 200;
            }

            /**
             * Builds the {@code (tagName, value) -> keep?} predicate from properties. An empty
             * allowlist keeps the v0.1 pass-through default; a non-empty allowlist filters the
             * {@code event_type} tag and ignores other tag names.
             */
            private static BiPredicate<String, String> buildPredicate(List<String> allowlist) {
                // The Spring binder materializes an empty list when the property is absent
                // (@DefaultValue on a List), so null is not a configuration we have to defend
                // against here.
                if (allowlist.isEmpty()) {
                    return (tag, value) -> true;
                }
                Set<String> allowed = Set.copyOf(allowlist);
                return (tag, value) -> !"event_type".equals(tag) || allowed.contains(value);
            }
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
        static final String UNKNOWN_INSTRUMENTATION_VERSION = "unknown";

        @Bean
        static TracingBeanPostProcessor outboxTracingBeanPostProcessor(
                ObjectProvider<OpenTelemetry> openTelemetryProvider) {
            return new TracingBeanPostProcessor(openTelemetryProvider);
        }

        /**
         * BeanPostProcessor with a cached {@link Tracer} resolved on first use. Caching matters
         * because the BPP can be invoked many times during context startup (every bean), and
         * resolving the {@code Tracer} from the {@link OpenTelemetry} provider on every call is
         * pointless.
         *
         * <p>Implements {@link Ordered} with {@link Ordered#LOWEST_PRECEDENCE} {@code - 100} so it
         * runs <em>after</em> the metrics BPP (higher order value is invoked later), wrapping the
         * already-metered delegate. See ADR-0017.
         */
        static final class TracingBeanPostProcessor implements BeanPostProcessor, Ordered {

            private final ObjectProvider<OpenTelemetry> openTelemetryProvider;
            private volatile Tracer tracer;

            TracingBeanPostProcessor(ObjectProvider<OpenTelemetry> openTelemetryProvider) {
                this.openTelemetryProvider = openTelemetryProvider;
            }

            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof Outbox outbox && !(bean instanceof TracedOutbox)) {
                    return new TracedOutbox(outbox, resolveTracer());
                }
                return bean;
            }

            @Override
            public int getOrder() {
                return Ordered.LOWEST_PRECEDENCE - 100;
            }

            private Tracer resolveTracer() {
                Tracer cached = this.tracer;
                if (cached != null) {
                    return cached;
                }
                synchronized (this) {
                    if (this.tracer == null) {
                        this.tracer =
                                openTelemetryProvider
                                        .getObject()
                                        .tracerBuilder(TRACER_INSTRUMENTATION_SCOPE_NAME)
                                        .setInstrumentationVersion(resolveInstrumentationVersion())
                                        .build();
                    }
                    return this.tracer;
                }
            }
        }

        static String resolveInstrumentationVersion() {
            String version = OutboxAutoConfiguration.class.getPackage().getImplementationVersion();
            return version != null ? version : UNKNOWN_INSTRUMENTATION_VERSION;
        }
    }

    /**
     * Registers an {@link OutboxHealthIndicator} when Spring Boot Actuator is on the classpath and
     * the standard {@code management.health.outbox.enabled} switch (plus our own opt-out) allows
     * it.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(AbstractHealthIndicator.class)
    @ConditionalOnEnabledHealthIndicator("outbox")
    @ConditionalOnProperty(
            prefix = "io.github.lobofoltran.outbox.health",
            name = "enabled",
            matchIfMissing = true)
    static class HealthConfiguration {

        @Bean(name = "outboxHealthIndicator")
        @ConditionalOnMissingBean(name = "outboxHealthIndicator")
        OutboxHealthIndicator outboxHealthIndicator(
                DataSource dataSource, OutboxProperties properties) {
            return new OutboxHealthIndicator(
                    dataSource, properties.schema(), properties.tableName());
        }
    }
}
