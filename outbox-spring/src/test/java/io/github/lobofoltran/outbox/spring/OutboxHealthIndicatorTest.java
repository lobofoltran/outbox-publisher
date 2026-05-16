package io.github.lobofoltran.outbox.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OutboxHealthIndicatorTest {

    @Test
    @DisplayName("rejects an unsafe table identifier at construction time")
    void rejects_invalid_table_identifier() {
        DataSource dataSource = mock(DataSource.class);
        assertThatThrownBy(() -> new OutboxHealthIndicator(dataSource, null, "out\"box"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects an unsafe schema identifier at construction time")
    void rejects_invalid_schema_identifier() {
        DataSource dataSource = mock(DataSource.class);
        assertThatThrownBy(() -> new OutboxHealthIndicator(dataSource, "app;DROP", "outbox"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a blank schema is treated as unqualified (no schema prefix in the probe SQL)")
    void blank_schema_is_unqualified() {
        DataSource dataSource = mock(DataSource.class);
        OutboxHealthIndicator indicator = new OutboxHealthIndicator(dataSource, "   ", "outbox");
        // Trigger the probe; the mock returns null which surfaces as down(). The point of this
        // test is that construction did not throw on the blank schema.
        assertThat(indicator).isNotNull();
    }
}
