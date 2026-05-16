/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OutboxTransientExceptionTest {

    @Test
    void preserves_message() {
        OutboxTransientException ex = new OutboxTransientException("conn lost");
        assertThat(ex.getMessage()).isEqualTo("conn lost");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    void preserves_message_and_cause() {
        IllegalStateException cause = new IllegalStateException("08006");
        OutboxTransientException ex = new OutboxTransientException("conn lost", cause);
        assertThat(ex.getMessage()).isEqualTo("conn lost");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void is_an_outbox_exception() {
        OutboxTransientException ex = new OutboxTransientException("x");
        assertThat(ex).isInstanceOf(OutboxException.class).isInstanceOf(RuntimeException.class);
    }
}
