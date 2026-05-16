package io.github.lobofoltran.outbox.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;

import io.github.lobofoltran.outbox.Outbox;
import io.github.lobofoltran.outbox.OutboxEvent;
import io.github.lobofoltran.outbox.jdbc.JdbcOutbox;
import io.github.lobofoltran.outbox.micrometer.MeteredOutbox;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class OutboxAutoConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration.class))
                    .withBean(DataSource.class, () -> mock(DataSource.class));

    @Test
    @DisplayName("publishes an Outbox bean with default configuration")
    void publishes_outbox_bean_with_defaults() {
        runner.run(
                context -> {
                    assertThat(context).hasSingleBean(Outbox.class);
                    assertThat(context).hasSingleBean(OutboxProperties.class);
                    OutboxProperties props = context.getBean(OutboxProperties.class);
                    assertThat(props.enabled()).isTrue();
                    assertThat(props.tableName()).isEqualTo("outbox");
                    assertThat(props.schema()).isNull();
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
        runner.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(Outbox.class);
                            assertThat(context.getBean(Outbox.class))
                                    .isInstanceOf(MeteredOutbox.class);
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

    static class StubOutbox implements Outbox {

        @Override
        public void publish(OutboxEvent event) {
            // no-op stub for the backs-off-on-user-bean test
        }
    }
}
