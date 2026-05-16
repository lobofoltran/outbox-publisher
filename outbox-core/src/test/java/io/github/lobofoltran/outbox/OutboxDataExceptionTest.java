package io.github.lobofoltran.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OutboxDataExceptionTest {

    @Test
    void preserves_message() {
        OutboxDataException ex = new OutboxDataException("bad payload");
        assertThat(ex.getMessage()).isEqualTo("bad payload");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    void preserves_message_and_cause() {
        IllegalStateException cause = new IllegalStateException("22001");
        OutboxDataException ex = new OutboxDataException("bad payload", cause);
        assertThat(ex.getMessage()).isEqualTo("bad payload");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void is_an_outbox_exception() {
        OutboxDataException ex = new OutboxDataException("x");
        assertThat(ex).isInstanceOf(OutboxException.class).isInstanceOf(RuntimeException.class);
    }
}
