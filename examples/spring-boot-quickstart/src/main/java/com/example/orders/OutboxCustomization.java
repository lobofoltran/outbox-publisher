package com.example.orders;

import org.springframework.context.annotation.Configuration;

/**
 * Placeholder for a {@code JdbcOutboxBuilderCustomizer} bean.
 *
 * <p>TODO(agent-14): once {@code outbox-spring} ships {@code JdbcOutboxBuilderCustomizer}
 * (Agent 14 / {@code feat/spring-autoconfig-overhaul}), uncomment and adapt the
 * sketch below to override the {@code IdGenerator} (or any other builder knob)
 * without losing the autoconfigured defaults.
 *
 * <pre>{@code
 * import io.github.lobofoltran.outbox.jdbc.IdGenerator;
 * import io.github.lobofoltran.outbox.spring.JdbcOutboxBuilderCustomizer;
 * import org.springframework.context.annotation.Bean;
 *
 * @Bean
 * JdbcOutboxBuilderCustomizer randomUuidIdGenerator() {
 *     return builder -> builder.idGenerator(clock -> java.util.UUID.randomUUID());
 * }
 * }</pre>
 */
@Configuration
public class OutboxCustomization {
    // Intentionally empty until JdbcOutboxBuilderCustomizer is published.
}
