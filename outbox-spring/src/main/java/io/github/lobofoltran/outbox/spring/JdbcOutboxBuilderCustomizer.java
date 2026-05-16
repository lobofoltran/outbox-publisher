/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox.spring;

import io.github.lobofoltran.outbox.jdbc.JdbcOutbox;

/**
 * Callback for advanced customization of the auto-configured {@link JdbcOutbox} bean.
 *
 * <p>Beans implementing this interface are picked up by {@link OutboxAutoConfiguration} and invoked
 * in {@link org.springframework.core.annotation.Order Order}-aware sequence after {@link
 * OutboxProperties} have been applied to the builder and before {@link JdbcOutbox.Builder#build()
 * build()} is called. At callback time the builder already has its {@code connectionSupplier},
 * {@code tableName}, and (when configured) {@code schema} set, so customizers can safely call
 * {@link JdbcOutbox.Builder#idGenerator(io.github.lobofoltran.outbox.jdbc.IdGenerator)
 * idGenerator(...)}, {@link JdbcOutbox.Builder#clock(java.time.Clock) clock(...)}, or {@link
 * JdbcOutbox.Builder#dialect(io.github.lobofoltran.outbox.jdbc.spi.OutboxDialect) dialect(...)}
 * without redoing the wiring already performed by the autoconfig.
 *
 * <p>Typical use cases include injecting a deterministic clock for tests, plugging a custom id
 * generator (e.g. ULID), or pinning a non-default dialect to bypass auto-detection.
 *
 * <p>This is the recommended extension point for adopters who want to tweak the auto-configured
 * {@code JdbcOutbox} without registering their own {@code @Bean Outbox} (which would back off the
 * autoconfig entirely).
 *
 * @since 0.2.0
 */
@FunctionalInterface
public interface JdbcOutboxBuilderCustomizer {

    /**
     * Apply this customizer to the given builder.
     *
     * @param builder builder for the auto-configured {@link JdbcOutbox}; never {@code null}.
     */
    void customize(JdbcOutbox.Builder builder);
}
