package io.github.lobofoltran.outbox.jdbc.internal;

import java.util.Map;

/**
 * Hand-rolled JSON serializer for the {@code headers} column.
 *
 * <p>Per ADR-0002 (B1) the library serializes {@code Map<String, String>} without pulling Jackson
 * into the runtime path. The input is constrained to string keys and string values, which means we
 * only need RFC 8259 string escaping — no polymorphism, no number formatting, no date handling.
 *
 * <p>Output is a single-line JSON object preserving the iteration order of the input map. Callers
 * send the resulting string to PostgreSQL with an explicit {@code ?::jsonb} cast.
 */
public final class HeadersJsonWriter {

    private static final String EMPTY_OBJECT = "{}";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private HeadersJsonWriter() {}

    /** Returns the JSON object representation of {@code headers}. */
    public static String toJson(Map<String, String> headers) {
        if (headers.isEmpty()) {
            return EMPTY_OBJECT;
        }
        StringBuilder out = new StringBuilder(estimatedSize(headers));
        out.append('{');
        boolean first = true;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (!first) {
                out.append(',');
            }
            out.append('"');
            escape(entry.getKey(), out);
            out.append('"').append(':').append('"');
            escape(entry.getValue(), out);
            out.append('"');
            first = false;
        }
        out.append('}');
        return out.toString();
    }

    private static int estimatedSize(Map<String, String> headers) {
        int size = 2 + headers.size() * 6;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            size += entry.getKey().length() + entry.getValue().length();
        }
        return size;
    }

    private static void escape(String value, StringBuilder out) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\b':
                    out.append("\\b");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        appendUnicodeEscape(c, out);
                    } else {
                        out.append(c);
                    }
            }
        }
    }

    private static void appendUnicodeEscape(char c, StringBuilder out) {
        out.append("\\u")
                .append(HEX[(c >> 12) & 0xF])
                .append(HEX[(c >> 8) & 0xF])
                .append(HEX[(c >> 4) & 0xF])
                .append(HEX[c & 0xF]);
    }
}
