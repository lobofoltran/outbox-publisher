/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OutboxValidationExceptionTest {

    @Test
    void preserves_message() {
        OutboxValidationException ex =
                new OutboxValidationException("aggregateType must not be null");
        assertThat(ex.getMessage()).isEqualTo("aggregateType must not be null");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    void preserves_message_and_cause() {
        IllegalStateException cause = new IllegalStateException("upstream");
        OutboxValidationException ex = new OutboxValidationException("blank field", cause);
        assertThat(ex.getMessage()).isEqualTo("blank field");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void is_an_outbox_exception() {
        OutboxValidationException ex = new OutboxValidationException("x");
        assertThat(ex).isInstanceOf(OutboxException.class).isInstanceOf(RuntimeException.class);
    }
}
