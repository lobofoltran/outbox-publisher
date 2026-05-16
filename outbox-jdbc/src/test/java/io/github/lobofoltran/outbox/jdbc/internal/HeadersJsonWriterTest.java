/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox.jdbc.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class HeadersJsonWriterTest {

    @Test
    void empty_map_serializes_to_empty_object() {
        assertThat(HeadersJsonWriter.toJson(Map.of())).isEqualTo("{}");
    }

    @Test
    void single_pair_serializes_as_one_entry() {
        assertThat(HeadersJsonWriter.toJson(Map.of("k", "v"))).isEqualTo("{\"k\":\"v\"}");
    }

    @Test
    void preserves_insertion_order() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("a", "1");
        headers.put("b", "2");
        headers.put("c", "3");
        assertThat(HeadersJsonWriter.toJson(headers))
                .isEqualTo("{\"a\":\"1\",\"b\":\"2\",\"c\":\"3\"}");
    }

    @Test
    void escapes_double_quote() {
        assertThat(HeadersJsonWriter.toJson(Map.of("a", "\"x\"")))
                .isEqualTo("{\"a\":\"\\\"x\\\"\"}");
    }

    @Test
    void escapes_backslash() {
        assertThat(HeadersJsonWriter.toJson(Map.of("a", "C:\\Windows"))).contains("C:\\\\Windows");
    }

    @Test
    void escapes_control_chars_using_short_form() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("bs", "\b");
        headers.put("ff", "\f");
        headers.put("nl", "\n");
        headers.put("cr", "\r");
        headers.put("tab", "\t");
        String json = HeadersJsonWriter.toJson(headers);
        assertThat(json)
                .contains("\\b")
                .contains("\\f")
                .contains("\\n")
                .contains("\\r")
                .contains("\\t");
    }

    @Test
    void escapes_other_control_chars_using_unicode_form() {
        Map<String, String> headers = Map.of("ctrl", "\u0001\u001F");
        String json = HeadersJsonWriter.toJson(headers);
        assertThat(json).contains("\\u0001").contains("\\u001f");
    }

    @Test
    void passes_non_ascii_unicode_through_unescaped() {
        Map<String, String> headers = Map.of("emoji", "café 🙂");
        String json = HeadersJsonWriter.toJson(headers);
        assertThat(json).contains("café").contains("🙂");
    }

    @Test
    void escapes_key_too() {
        Map<String, String> headers = Map.of("with\"quote", "v");
        assertThat(HeadersJsonWriter.toJson(headers)).contains("\\\"quote");
    }
}
