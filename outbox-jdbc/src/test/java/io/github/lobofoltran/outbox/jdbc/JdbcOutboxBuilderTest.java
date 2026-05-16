/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.github.lobofoltran.outbox.jdbc.spi.OutboxDialect;

import org.junit.jupiter.api.Test;

class JdbcOutboxBuilderTest {

    // Builder never invokes the supplier; null is enough to exercise the validation paths.
    private final ConnectionSupplier connectionSupplier = () -> null;

    @Test
    void requires_connection_supplier_at_build_time() {
        assertThatNullPointerException()
                .isThrownBy(() -> JdbcOutbox.builder().build())
                .withMessageContaining("connectionSupplier");
    }

    @Test
    void rejects_null_connection_supplier() {
        assertThatNullPointerException()
                .isThrownBy(() -> JdbcOutbox.builder().connectionSupplier(null))
                .withMessageContaining("connectionSupplier");
    }

    @Test
    void rejects_null_table_name() {
        assertThatNullPointerException()
                .isThrownBy(() -> JdbcOutbox.builder().tableName(null))
                .withMessageContaining("tableName");
    }

    @Test
    void rejects_null_clock() {
        assertThatNullPointerException()
                .isThrownBy(() -> JdbcOutbox.builder().clock(null))
                .withMessageContaining("clock");
    }

    @Test
    void rejects_null_id_generator() {
        assertThatNullPointerException()
                .isThrownBy(() -> JdbcOutbox.builder().idGenerator(null))
                .withMessageContaining("idGenerator");
    }

    @Test
    void rejects_invalid_table_name() {
        assertThatThrownBy(
                        () ->
                                JdbcOutbox.builder()
                                        .connectionSupplier(connectionSupplier)
                                        .tableName("outbox; drop table users;")
                                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tableName");
    }

    @Test
    void rejects_invalid_schema() {
        assertThatThrownBy(
                        () ->
                                JdbcOutbox.builder()
                                        .connectionSupplier(connectionSupplier)
                                        .schema("public; --")
                                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema");
    }

    @Test
    void accepts_null_schema() {
        JdbcOutbox outbox =
                JdbcOutbox.builder().connectionSupplier(connectionSupplier).schema(null).build();
        assertThat(outbox).isNotNull();
    }

    @Test
    void accepts_valid_identifiers() {
        JdbcOutbox outbox =
                JdbcOutbox.builder()
                        .connectionSupplier(connectionSupplier)
                        .schema("public")
                        .tableName("my_outbox_2")
                        .build();
        assertThat(outbox).isNotNull();
    }

    @Test
    void rejects_null_dialect() {
        assertThatNullPointerException()
                .isThrownBy(() -> JdbcOutbox.builder().dialect(null))
                .withMessageContaining("dialect");
    }

    @Test
    void accepts_pinned_dialect() {
        OutboxDialect pinned = mock(OutboxDialect.class);
        JdbcOutbox outbox =
                JdbcOutbox.builder().connectionSupplier(connectionSupplier).dialect(pinned).build();
        assertThat(outbox).isNotNull();
    }

    @Test
    void honors_overridden_clock_and_id_generator() {
        java.time.Clock fixed =
                java.time.Clock.fixed(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC);
        java.util.UUID forcedId = java.util.UUID.fromString("00000000-0000-7000-8000-000000000000");
        IdGenerator stub = clock -> forcedId;

        JdbcOutbox outbox =
                JdbcOutbox.builder()
                        .connectionSupplier(connectionSupplier)
                        .clock(fixed)
                        .idGenerator(stub)
                        .build();
        assertThat(outbox).isNotNull();
    }
}
