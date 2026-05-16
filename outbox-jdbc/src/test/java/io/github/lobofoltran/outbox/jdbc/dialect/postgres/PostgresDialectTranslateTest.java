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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PostgresDialectTranslateTest {

    private final PostgresDialect dialect = new PostgresDialect();

    @ParameterizedTest(name = "SQLState {0} -> {1}")
    @CsvSource({
        // Integrity violations (23xxx) — except 23502 which is configuration.
        "23505, INTEGRITY",
        "23503, INTEGRITY",
        "23000, INTEGRITY",
        "23999, INTEGRITY",
        // not_null_violation: caller violated schema contract.
        "23502, CONFIGURATION",
        // Connection failures.
        "08000, TRANSIENT",
        "08006, TRANSIENT",
        // Transient deadlock/serialization (class 40).
        "40001, TRANSIENT",
        "40P01, TRANSIENT",
        // Data exceptions.
        "22001, DATA",
        "22023, DATA",
        "21000, DATA",
        // Configuration: undefined object/feature/syntax (class 42).
        "42P01, CONFIGURATION",
        "42601, CONFIGURATION",
        // Insufficient resources (class 53).
        "53100, CONFIGURATION",
        // Class 57 — operator intervention: most are configuration, failover codes are transient.
        "57P04, CONFIGURATION",
        "57000, CONFIGURATION",
        "57P01, TRANSIENT",
        "57P02, TRANSIENT",
        "57P03, TRANSIENT",
        "57014, TRANSIENT",
        // System errors (class 58).
        "58000, CONFIGURATION",
        // Invalid catalog name (3D).
        "3D000, CONFIGURATION",
        // Configuration file errors (F0).
        "F0000, CONFIGURATION",
        // Unknown/default.
        "XX000, UNKNOWN",
        "99999, UNKNOWN"
    })
    void translates_sqlstate_to_expected_category(String sqlState, String category) {
        OutboxException result = dialect.translate(new SQLException("boom", sqlState), "ctx");

        assertThat(result).hasMessageContaining("ctx").hasMessageContaining(sqlState);
        assertThat(result).isInstanceOf(expectedType(category));
        if ("UNKNOWN".equals(category)) {
            assertThat(result).isExactlyInstanceOf(OutboxException.class);
        }
    }

    @ParameterizedTest(name = "non-5-char SQLState \"{0}\" -> {1}")
    @CsvSource({
        // Short states: fall through exact-match guard and use prefix logic (or default).
        "'', UNKNOWN",
        "'2', UNKNOWN",
        "'23', INTEGRITY",
        "'234', INTEGRITY",
        // 6-char (non-standard) state still falls back to the prefix path.
        "'235021', INTEGRITY"
    })
    void non_five_char_sqlstate_uses_prefix_path(String sqlState, String category) {
        OutboxException result = dialect.translate(new SQLException("boom", sqlState), "ctx");

        assertThat(result).isInstanceOf(expectedType(category));
    }

    @org.junit.jupiter.api.Test
    void null_sqlstate_falls_back_to_default() {
        OutboxException result = dialect.translate(new SQLException("nostate"), "ctx");

        assertThat(result).isExactlyInstanceOf(OutboxException.class);
        assertThat(result).hasMessageContaining("ctx").hasMessageContaining("null");
    }

    private static Class<? extends OutboxException> expectedType(String category) {
        return switch (category) {
            case "INTEGRITY" -> OutboxIntegrityException.class;
            case "TRANSIENT" -> OutboxTransientException.class;
            case "DATA" -> OutboxDataException.class;
            case "CONFIGURATION" -> OutboxConfigurationException.class;
            case "UNKNOWN" -> OutboxException.class;
            default -> throw new IllegalArgumentException("unknown category: " + category);
        };
    }
}
