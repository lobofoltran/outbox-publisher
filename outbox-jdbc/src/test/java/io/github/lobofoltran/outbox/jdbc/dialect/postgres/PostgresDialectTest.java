/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox.jdbc.dialect.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import io.github.lobofoltran.outbox.OutboxConfigurationException;
import io.github.lobofoltran.outbox.OutboxDataException;
import io.github.lobofoltran.outbox.OutboxException;
import io.github.lobofoltran.outbox.OutboxIntegrityException;
import io.github.lobofoltran.outbox.OutboxTransientException;
import io.github.lobofoltran.outbox.jdbc.spi.DialectCapability;

import org.junit.jupiter.api.Test;

class PostgresDialectTest {

    private final PostgresDialect dialect = new PostgresDialect();

    @Test
    void translate_unique_violation_to_integrity_exception() {
        OutboxException result = dialect.translate(new SQLException("dup", "23505"), "ctx");
        assertThat(result).isInstanceOf(OutboxIntegrityException.class);
        assertThat(result).hasMessageContaining("ctx").hasMessageContaining("23505");
    }

    @Test
    void translate_serialization_failure_to_transient_exception() {
        assertThat(dialect.translate(new SQLException("retry", "40001"), "ctx"))
                .isInstanceOf(OutboxTransientException.class);
    }

    @Test
    void translate_connection_failure_to_transient_exception() {
        assertThat(dialect.translate(new SQLException("conn", "08006"), "ctx"))
                .isInstanceOf(OutboxTransientException.class);
    }

    @Test
    void translate_data_exception_to_data_exception() {
        assertThat(dialect.translate(new SQLException("bad", "22001"), "ctx"))
                .isInstanceOf(OutboxDataException.class);
    }

    @Test
    void translate_cardinality_violation_to_data_exception() {
        assertThat(dialect.translate(new SQLException("card", "21000"), "ctx"))
                .isInstanceOf(OutboxDataException.class);
    }

    @Test
    void translate_undefined_table_to_configuration_exception() {
        assertThat(dialect.translate(new SQLException("missing", "42P01"), "ctx"))
                .isInstanceOf(OutboxConfigurationException.class);
    }

    @Test
    void translate_invalid_schema_to_configuration_exception() {
        assertThat(dialect.translate(new SQLException("missing", "3D000"), "ctx"))
                .isInstanceOf(OutboxConfigurationException.class);
    }

    @Test
    void translate_unknown_sqlstate_falls_back_to_base_outbox_exception() {
        OutboxException ex = dialect.translate(new SQLException("weird", "XX999"), "ctx");
        assertThat(ex).isExactlyInstanceOf(OutboxException.class);
    }

    @Test
    void translate_null_sqlstate_falls_back_to_base_outbox_exception() {
        OutboxException ex = dialect.translate(new SQLException("nostate"), "ctx");
        assertThat(ex).isExactlyInstanceOf(OutboxException.class);
    }

    @Test
    void translate_short_sqlstate_uses_full_state_as_prefix() {
        // Single-character SQLState — exercises the (state.length() >= 2 ? substring : state)
        // branch in the dialect's prefix extraction.
        OutboxException ex = dialect.translate(new SQLException("oddstate", "X"), "ctx");
        assertThat(ex).isExactlyInstanceOf(OutboxException.class);
    }

    @Test
    void capabilities_advertise_idempotency_jsonb_uuid_tz_and_batch() {
        assertThat(dialect.capabilities())
                .containsExactlyInAnyOrder(
                        DialectCapability.UPSERT_ON_CONFLICT,
                        DialectCapability.NATIVE_JSON,
                        DialectCapability.NATIVE_UUID,
                        DialectCapability.TIMESTAMP_WITH_TIMEZONE,
                        DialectCapability.BATCH_INSERT);
    }
}
