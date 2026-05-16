/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OutboxIntegrityExceptionTest {

    @Test
    void preserves_message() {
        OutboxIntegrityException ex = new OutboxIntegrityException("dup id");
        assertThat(ex.getMessage()).isEqualTo("dup id");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    void preserves_message_and_cause() {
        IllegalStateException cause = new IllegalStateException("23505");
        OutboxIntegrityException ex = new OutboxIntegrityException("dup id", cause);
        assertThat(ex.getMessage()).isEqualTo("dup id");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void is_an_outbox_exception() {
        OutboxIntegrityException ex = new OutboxIntegrityException("x");
        assertThat(ex).isInstanceOf(OutboxException.class).isInstanceOf(RuntimeException.class);
    }
}
