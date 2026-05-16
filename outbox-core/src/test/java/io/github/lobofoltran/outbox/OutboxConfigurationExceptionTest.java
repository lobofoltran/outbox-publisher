/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OutboxConfigurationExceptionTest {

    @Test
    void preserves_message() {
        OutboxConfigurationException ex = new OutboxConfigurationException("missing table");
        assertThat(ex.getMessage()).isEqualTo("missing table");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    void preserves_message_and_cause() {
        IllegalStateException cause = new IllegalStateException("42P01");
        OutboxConfigurationException ex = new OutboxConfigurationException("missing table", cause);
        assertThat(ex.getMessage()).isEqualTo("missing table");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void is_an_outbox_exception() {
        OutboxConfigurationException ex = new OutboxConfigurationException("x");
        assertThat(ex).isInstanceOf(OutboxException.class).isInstanceOf(RuntimeException.class);
    }
}
