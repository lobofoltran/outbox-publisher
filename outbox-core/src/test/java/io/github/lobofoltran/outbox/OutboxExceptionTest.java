package io.github.lobofoltran.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OutboxExceptionTest {

    @Test
    void preserves_message() {
        OutboxException ex = new OutboxException("boom");
        assertThat(ex.getMessage()).isEqualTo("boom");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    void preserves_message_and_cause() {
        IllegalStateException cause = new IllegalStateException("inner");
        OutboxException ex = new OutboxException("boom", cause);
        assertThat(ex.getMessage()).isEqualTo("boom");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void is_runtime_exception() {
        assertThat(new OutboxException("x")).isInstanceOf(RuntimeException.class);
    }
}
