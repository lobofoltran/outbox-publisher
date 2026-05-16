/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;

import io.github.lobofoltran.outbox.Outbox;
import io.github.lobofoltran.outbox.OutboxEvent;
import io.github.lobofoltran.outbox.jdbc.JdbcOutbox;
import io.github.lobofoltran.outbox.micrometer.MeteredOutbox;
import io.github.lobofoltran.outbox.otel.TracedOutbox;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

class OutboxAutoConfigurationTest {

    // Default the shared runner with tracing disabled. After the DEBT-03 fix, the TracedOutbox
    // BPP is no longer gated on the presence of an OpenTelemetry bean (it falls back to
    // GlobalOpenTelemetry.get()), so every bean would otherwise be wrapped — which would clobber
    // the metrics-/user-bean assertions in most tests in this class. Tracing-specific tests turn
    // tracing back on explicitly via withPropertyValues(...).
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration.class))
                    .withBean(DataSource.class, () -> mock(DataSource.class))
                    .withPropertyValues("io.github.lobofoltran.outbox.tracing.enabled=false");

    @Test
    @DisplayName("publishes an Outbox bean with default configuration")
    void publishes_outbox_bean_with_defaults() {
        // Use a fresh runner without the class-level tracing override so we observe the actual
        // property defaults.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration.class))
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(Outbox.class);
                            assertThat(context).hasSingleBean(OutboxProperties.class);
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
    @DisplayName("honors overridden table name and schema properties")
    void honors_overridden_properties() {
        runner.withPropertyValues(
                        "io.github.lobofoltran.outbox.table-name=custom_outbox",
                        "io.github.lobofoltran.outbox.schema=app")
                .run(
                        context -> {
                            OutboxProperties props = context.getBean(OutboxProperties.class);
                            assertThat(props.tableName()).isEqualTo("custom_outbox");
                            assertThat(props.schema()).isEqualTo("app");
                            assertThat(context).hasSingleBean(Outbox.class);
                        });
    }

    @Test
    @DisplayName("treats blank schema as unqualified")
    void blank_schema_is_treated_as_unqualified() {
        runner.withPropertyValues("io.github.lobofoltran.outbox.schema=   ")
                .run(context -> assertThat(context).hasSingleBean(Outbox.class));
    }

    @Test
    @DisplayName("backs off entirely when io.github.lobofoltran.outbox.enabled=false")
    void backs_off_when_disabled() {
        runner.withPropertyValues("io.github.lobofoltran.outbox.enabled=false")
                .run(
                        context -> {
                            assertThat(context).doesNotHaveBean(Outbox.class);
                            assertThat(context).doesNotHaveBean(OutboxProperties.class);
                        });
    }

    @Test
    @DisplayName("backs off when JdbcOutbox is not on the classpath")
    void backs_off_when_jdbc_outbox_is_missing() {
        runner.withClassLoader(new FilteredClassLoader(JdbcOutbox.class))
                .run(context -> assertThat(context).doesNotHaveBean(Outbox.class));
    }

    @Test
    @DisplayName("backs off when no DataSource bean is in the context")
    void backs_off_when_no_datasource() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(Outbox.class));
    }

    @Test
    @DisplayName("backs off when the user already registered an Outbox bean")
    void user_provided_outbox_wins() {
        runner.withUserConfiguration(UserOutboxConfig.class)
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(Outbox.class);
                            Outbox bean = context.getBean(Outbox.class);
                            assertThat(bean).isInstanceOf(StubOutbox.class);
                        });
    }

    @Test
    @DisplayName("wraps the Outbox bean with MeteredOutbox when a MeterRegistry is present")
    void wraps_with_metered_outbox_when_micrometer_is_available() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        runner.withBean(MeterRegistry.class, () -> registry)
                .withUserConfiguration(UserOutboxConfig.class)
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(Outbox.class);
                            Outbox bean = context.getBean(Outbox.class);
                            assertThat(bean).isInstanceOf(MeteredOutbox.class);
                            // Exercise the default (empty allowlist) pass-through predicate so
                            // the underlying always-true lambda is actually invoked at publish
                            // time, not merely wired in at bean creation.
                            bean.publish(
                                    OutboxEvent.builder()
                                            .aggregateType("Order")
                                            .aggregateId("agg-1")
                                            .eventType("OrderPlaced")
                                            .contentType("application/json")
                                            .payload(new byte[] {1})
                                            .occurredAt(Instant.parse("2026-03-10T08:30:00Z"))
                                            .build());
                            assertThat(
                                            registry.find("outbox.publish")
                                                    .tag("event_type", "OrderPlaced")
                                                    .timer()
                                                    .count())
                                    .isEqualTo(1);
                        });
    }

    @Test
    @DisplayName("leaves the Outbox bean bare when no MeterRegistry is in the context")
    void does_not_wrap_when_no_meter_registry() {
        runner.run(
                context -> {
                    assertThat(context).hasSingleBean(Outbox.class);
                    assertThat(context.getBean(Outbox.class)).isNotInstanceOf(MeteredOutbox.class);
                });
    }

    @Test
    @DisplayName("respects metrics.enabled=false even when Micrometer is available")
    void opts_out_when_metrics_enabled_is_false() {
        runner.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues("io.github.lobofoltran.outbox.metrics.enabled=false")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(Outbox.class);
                            assertThat(context.getBean(Outbox.class))
                                    .isNotInstanceOf(MeteredOutbox.class);
                        });
    }

    @Test
    @DisplayName(
            "metrics cardinality cap: event-type-allowlist collapses non-listed values into the"
                    + " configured fallback tag string")
    void metrics_cardinality_cap_collapses_disallowed_event_types() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        runner.withBean(MeterRegistry.class, () -> registry)
                .withPropertyValues(
                        "io.github.lobofoltran.outbox.metrics.tag-fallback=BUCKET",
                        "io.github.lobofoltran.outbox.metrics.event-type-allowlist[0]=OrderPlaced")
                .withUserConfiguration(UserOutboxConfig.class)
                .run(
                        context -> {
                            Outbox bean = context.getBean(Outbox.class);
                            assertThat(bean).isInstanceOf(MeteredOutbox.class);

                            bean.publish(
                                    OutboxEvent.builder()
                                            .aggregateType("Order")
                                            .aggregateId("agg-1")
                                            .eventType("user-derived-1")
                                            .contentType("application/json")
                                            .payload(new byte[] {1})
                                            .occurredAt(Instant.parse("2026-03-10T08:30:00Z"))
                                            .build());
                            bean.publish(
                                    OutboxEvent.builder()
                                            .aggregateType("Order")
                                            .aggregateId("agg-1")
                                            .eventType("user-derived-2")
                                            .contentType("application/json")
                                            .payload(new byte[] {1})
                                            .occurredAt(Instant.parse("2026-03-10T08:30:00Z"))
                                            .build());
                            // Allowed event_type — passes through.
                            bean.publish(
                                    OutboxEvent.builder()
                                            .aggregateType("Order")
                                            .aggregateId("agg-1")
                                            .eventType("OrderPlaced")
                                            .contentType("application/json")
                                            .payload(new byte[] {1})
                                            .occurredAt(Instant.parse("2026-03-10T08:30:00Z"))
                                            .build());

                            // The two disallowed values collapsed into the BUCKET fallback timer.
                            assertThat(
                                            registry.find("outbox.publish")
                                                    .tag("event_type", "BUCKET")
                                                    .timer()
                                                    .count())
                                    .isEqualTo(2);
                            // The allowed value has its own dedicated series.
                            assertThat(
                                            registry.find("outbox.publish")
                                                    .tag("event_type", "OrderPlaced")
                                                    .timer()
                                                    .count())
                                    .isEqualTo(1);
                            assertThat(
                                            registry.find("outbox.publish")
                                                    .tag("event_type", "user-derived-1")
                                                    .timer())
                                    .isNull();
                        });
    }

    @Test
    @DisplayName("does not double-wrap a user-provided MeteredOutbox")
    void does_not_double_wrap_existing_metered_outbox() {
        runner.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withUserConfiguration(UserMeteredOutboxConfig.class)
                .run(
                        context -> {
                            Outbox bean = context.getBean(Outbox.class);
                            assertThat(bean).isInstanceOf(MeteredOutbox.class);
                            assertThat(bean)
                                    .isSameAs(
                                            context.getBean(UserMeteredOutboxConfig.class)
                                                    .expected);
                        });
    }

    @Test
    @DisplayName("leaves the Outbox unwrapped when MeteredOutbox is not on the classpath")
    void does_not_wrap_when_outbox_micrometer_is_missing() {
        runner.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withClassLoader(new FilteredClassLoader(MeteredOutbox.class))
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(Outbox.class);
                            assertThat(context.getBean(Outbox.class))
                                    .isNotInstanceOf(MeteredOutbox.class);
                        });
    }

    @Test
    @DisplayName(
            "regression for DEBT-02: wraps with MeteredOutbox when the MeterRegistry is contributed"
                    + " by a *real* autoconfig (not pre-registered as a plain bean)")
    void wraps_when_meter_registry_comes_from_real_autoconfig() {
        // Reproduces the production failure mode: in 0.2.0 + Spring Boot 4 the Micrometer
        // autoconfigs (relocated to o.s.b.micrometer.metrics.autoconfigure.*) registered the
        // MeterRegistry *after* OutboxAutoConfiguration was considered, so
        // @ConditionalOnBean(MeterRegistry.class) evaluated false and the MetricsConfiguration was
        // silently skipped. Pre-registering the MeterRegistry via withBean(...) — as the older
        // tests do — masked the bug because the bean already existed before any autoconfig ran.
        //
        // Here we let the real Micrometer autoconfigs contribute the MeterRegistry, which only
        // works if OutboxAutoConfiguration declares an `afterName` relationship to them.
        new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(
                                MetricsAutoConfiguration.class,
                                CompositeMeterRegistryAutoConfiguration.class,
                                SimpleMetricsExportAutoConfiguration.class,
                                OutboxAutoConfiguration.class))
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(MeterRegistry.class);
                            assertThat(context).hasSingleBean(Outbox.class);
                            assertThat(context.getBean(Outbox.class))
                                    .isInstanceOf(MeteredOutbox.class);
                        });
    }

    @Test
    @DisplayName(
            "decoration order: TracedOutbox is outermost when both decorators are eligible"
                    + " (i.e. tracing wraps metrics, not the other way around)")
    void decoration_stack_traced_wraps_metered() {
        // The two decorator classes are peers (neither extends the other), so an `instanceof`
        // check on the publicly-exposed bean unambiguously identifies which one was applied
        // last. We assert TracedOutbox outermost AND not MeteredOutbox to lock the order down.
        // Inner layers are implied: both BeanPostProcessors fire (asserted in the dedicated
        // metrics-only and tracing-only tests) and the only way both can fire and produce a
        // TracedOutbox-typed bean is if metrics ran first and tracing ran second on top of it.
        runner.withPropertyValues("io.github.lobofoltran.outbox.tracing.enabled=true")
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(OpenTelemetry.class, OutboxAutoConfigurationTest::stubOpenTelemetry)
                .run(
                        context -> {
                            Outbox bean = context.getBean(Outbox.class);
                            assertThat(bean).isInstanceOf(TracedOutbox.class);
                            assertThat(bean).isNotInstanceOf(MeteredOutbox.class);
                        });
    }

    @Test
    @DisplayName(
            "tracing BPP caches the Tracer once and reuses it across multiple Outbox beans"
                    + " (covers the double-checked-locking fast path)")
    void tracing_bpp_caches_tracer_across_invocations() {
        runner.withPropertyValues("io.github.lobofoltran.outbox.tracing.enabled=true")
                .withBean(OpenTelemetry.class, OutboxAutoConfigurationTest::stubOpenTelemetry)
                .withUserConfiguration(TwoUserOutboxesConfig.class)
                .run(
                        context -> {
                            // With ConditionalOnMissingBean(Outbox.class) the autoconfig backs off,
                            // so only the two user Outboxes exist. Both must have been wrapped by
                            // TracedOutbox, exercising both the first-call (cache miss) and
                            // subsequent-call (cache hit) paths of resolveTracer().
                            assertThat(context.getBeansOfType(Outbox.class)).hasSize(2);
                            context.getBeansOfType(Outbox.class)
                                    .values()
                                    .forEach(o -> assertThat(o).isInstanceOf(TracedOutbox.class));
                        });
    }

    @Test
    @DisplayName("invokes JdbcOutboxBuilderCustomizer beans in @Order-aware sequence")
    void customizers_are_invoked_in_order() {
        runner.withUserConfiguration(OrderedCustomizersConfig.class)
                .run(
                        context -> {
                            OrderedCustomizersConfig config =
                                    context.getBean(OrderedCustomizersConfig.class);
                            assertThat(config.invocations).containsExactly("first", "second");
                        });
    }

    @Test
    @DisplayName("a customizer-supplied IdGenerator wins over the JdbcOutbox default")
    void customizer_supplied_id_generator_is_honored() {
        UUID forcedId = UUID.fromString("00000000-0000-7000-8000-000000000001");
        AtomicReference<JdbcOutbox.Builder> seenBuilder = new AtomicReference<>();
        runner.withBean(
                        JdbcOutboxBuilderCustomizer.class,
                        () ->
                                builder -> {
                                    seenBuilder.set(builder);
                                    builder.idGenerator((Clock c) -> forcedId);
                                    builder.clock(Clock.fixed(Instant.EPOCH, ZoneId.of("UTC")));
                                })
                .run(
                        context -> {
                            assertThat(seenBuilder.get()).isNotNull();
                            assertThat(context).hasSingleBean(Outbox.class);
                        });
    }

    private static OpenTelemetry stubOpenTelemetry() {
        return OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder().build())
                .build();
    }

    @Configuration
    static class UserOutboxConfig {

        @Bean
        Outbox outbox() {
            return new StubOutbox();
        }
    }

    @Configuration
    static class UserMeteredOutboxConfig {

        final MeteredOutbox expected =
                new MeteredOutbox(new StubOutbox(), new SimpleMeterRegistry());

        @Bean
        Outbox outbox() {
            return expected;
        }
    }

    @Configuration
    static class OrderedCustomizersConfig {

        final java.util.List<String> invocations = new java.util.ArrayList<>();

        @Bean
        @Order(2)
        JdbcOutboxBuilderCustomizer second() {
            return builder -> invocations.add("second");
        }

        @Bean
        @Order(1)
        JdbcOutboxBuilderCustomizer first() {
            return builder -> invocations.add("first");
        }
    }

    @Configuration
    static class TwoUserOutboxesConfig {

        @Bean
        Outbox firstOutbox() {
            return new StubOutbox();
        }

        @Bean
        Outbox secondOutbox() {
            return new StubOutbox();
        }
    }

    static class StubOutbox implements Outbox {

        @Override
        public void publish(OutboxEvent event) {
            // no-op stub for the backs-off-on-user-bean test
        }
    }
}
