package io.github.lobofoltran.outbox.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;

import io.github.lobofoltran.outbox.Outbox;
import io.github.lobofoltran.outbox.OutboxEvent;
import io.github.lobofoltran.outbox.jdbc.JdbcOutbox;

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

    @Configuration
    static class UserOutboxConfig {

        @Bean
        Outbox outbox() {
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
