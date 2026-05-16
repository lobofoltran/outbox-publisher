/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox.jdbc.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TableRefTest {

    @Test
    void qualified_returns_just_table_when_schema_is_null() {
        assertThat(new TableRef(null, "outbox").qualified()).isEqualTo("outbox");
    }

    @Test
    void qualified_returns_schema_dot_table_when_schema_is_set() {
        assertThat(new TableRef("app", "outbox").qualified()).isEqualTo("app.outbox");
    }

    @Test
    void rejects_null_table_name() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TableRef(null, null))
                .withMessageContaining("tableName");
    }

    @Test
    void rejects_invalid_table_name() {
        assertThatThrownBy(() -> new TableRef(null, "outbox; drop table users;"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tableName");
    }

    @Test
    void rejects_invalid_schema() {
        assertThatThrownBy(() -> new TableRef("public; --", "outbox"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema");
    }

    @Test
    void accepts_underscores_and_digits_in_identifiers() {
        TableRef ref = new TableRef("public_2", "my_outbox_v1");
        assertThat(ref.qualified()).isEqualTo("public_2.my_outbox_v1");
    }
}
