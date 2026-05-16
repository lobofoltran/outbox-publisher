/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox.jdbc.spi;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Reference to the target outbox table. Carries an optional {@code schema} qualifier and a required
 * {@code tableName}; both identifiers are validated against a strict allowlist so the dialect can
 * interpolate them safely into SQL.
 *
 * <p>The validation pattern is {@code [A-Za-z_][A-Za-z0-9_]*}. Anything else (quotes, dots, spaces,
 * semicolons, …) is rejected — preventing accidental SQL injection through misuse of the builder.
 *
 * @param schema optional schema qualifier; may be {@code null}.
 * @param tableName required, validated against the identifier pattern.
 * @since 0.1.0
 */
public record TableRef(String schema, String tableName) {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    public TableRef {
        Objects.requireNonNull(tableName, "tableName must not be null");
        requireIdentifier(tableName, "tableName");
        if (schema != null) {
            requireIdentifier(schema, "schema");
        }
    }

    /**
     * Returns the qualified table reference: {@code schema.tableName} or just {@code tableName}.
     *
     * @return the qualified table reference.
     * @since 0.1.0
     */
    public String qualified() {
        return schema != null ? schema + "." + tableName : tableName;
    }

    private static void requireIdentifier(String value, String name) {
        if (!IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    name + " must match " + IDENTIFIER.pattern() + " but was: " + value);
        }
    }
}
