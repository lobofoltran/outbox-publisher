/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox.jdbc.spi;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import io.github.lobofoltran.outbox.OutboxEvent;
import io.github.lobofoltran.outbox.OutboxException;

import org.junit.jupiter.api.Test;

/**
 * Default-method coverage for {@link OutboxDialect}. The contract has a no-op {@link
 * OutboxDialect#validate(OutboxEvent)} so out-of-tree dialects stay source-compatible — this test
 * pins that behaviour.
 */
class OutboxDialectTest {

    @Test
    void default_validate_is_a_no_op_for_arbitrary_input() {
        OutboxDialect dialect = new MinimalDialect();
        OutboxEvent event =
                OutboxEvent.builder()
                        .aggregateType("a".repeat(10_000))
                        .aggregateId("a".repeat(10_000))
                        .eventType("a".repeat(10_000))
                        .contentType("a".repeat(10_000))
                        .destination("a".repeat(10_000))
                        .payload(new byte[] {1})
                        .build();
        assertThatCode(() -> dialect.validate(event)).doesNotThrowAnyException();
    }

    /**
     * A minimal {@link OutboxDialect} that delegates all abstract methods to throwing stubs. Tests
     * in this file never invoke them — they only exercise {@link
     * OutboxDialect#validate(OutboxEvent)}.
     */
    private static final class MinimalDialect implements OutboxDialect {

        @Override
        public String insertSql(TableRef table) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void bindId(PreparedStatement statement, int index, UUID id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void bindHeaders(PreparedStatement statement, int index, String headersJson) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void bindTimestamp(PreparedStatement statement, int index, Instant value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void bindOptionalString(PreparedStatement statement, int index, String value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public OutboxException translate(SQLException ex, String contextMessage) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<DialectCapability> capabilities() {
            return Set.of();
        }
    }
}
