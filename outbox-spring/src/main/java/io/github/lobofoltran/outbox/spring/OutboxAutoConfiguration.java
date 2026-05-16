package io.github.lobofoltran.outbox.spring;

import javax.sql.DataSource;

import io.github.lobofoltran.outbox.Outbox;
import io.github.lobofoltran.outbox.jdbc.JdbcOutbox;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
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
}
