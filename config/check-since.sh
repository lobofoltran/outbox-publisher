#!/usr/bin/env bash
# Fails if any public Javadoc-able element in an exported package is missing
# an `@since` tag in its Javadoc.
#
# "Exported package" = any package listed under `exports ...` in a
# `module-info.java` of an `outbox-*` module. Packages whose names contain
# `.internal` or `.dialect.postgres` are skipped (internal SPI).
#
# A "public Javadoc-able element" here means: a public class / interface /
# enum / record / annotation declaration, OR a public method, constructor,
# or field declaration. We require a preceding `/** ... */` block that
# contains `@since`. If the element has no Javadoc block at all, that is a
# failure too — the Javadoc is part of the API contract for an exported
# package.
#
# The implementation parses Java files lightly: it scans for declarations
# at the top of a logical line whose access modifier is `public`, then
# walks upward to find the nearest preceding `*/` and the `/**` that opens
# that block. Inside the block, it checks for `@since`.
#
# This is not a full Java parser. It is intentionally line-oriented and
# conservative; if a declaration is hand-formatted in an unusual way the
# script may flag it. In that case, normalize the declaration to span one
# line (which Spotless / google-java-format already does in this repo).

set -euo pipefail

ROOT="${1:-$(pwd)}"
cd "$ROOT"

# 1. Discover exported packages from every module-info.java, excluding
#    `*.internal` and `*.dialect.postgres`.
EXPORTED_PKGS_RAW="$(
    find outbox-*/src/main/java -name module-info.java -print0 \
        | xargs -0 grep -hE '^[[:space:]]*exports[[:space:]]+' \
        | sed -E 's/^[[:space:]]*exports[[:space:]]+([^[:space:];]+).*$/\1/' \
        | grep -vE '\.internal($|\.)' \
        | grep -vE '\.dialect\.postgres($|\.)' \
        | sort -u
)"

if [[ -z "$EXPORTED_PKGS_RAW" ]]; then
    echo "check-since: no exported packages found; nothing to check." >&2
    exit 0
fi

# 2. Translate exported packages into source-file paths.
FILE_LIST_TMP="$(mktemp)"
trap 'rm -f "$FILE_LIST_TMP"' EXIT

while IFS= read -r pkg; do
    [[ -z "$pkg" ]] && continue
    pkg_path="${pkg//./\/}"
    while IFS= read -r -d '' f; do
        # Only files directly in the package (not a sub-package).
        rel="${f##*$pkg_path/}"
        if [[ "$rel" != *"/"* ]]; then
            printf '%s\n' "$f" >> "$FILE_LIST_TMP"
        fi
    done < <(find outbox-*/src/main/java -path "*/$pkg_path/*.java" -print0)
done <<< "$EXPORTED_PKGS_RAW"

# outbox-spring has no module-info (automatic module) but its public types
# are part of the API surface; check every .java file there too, excluding
# any `*.internal` package.
while IFS= read -r -d '' f; do
    if [[ "$f" != *"/internal/"* ]]; then
        printf '%s\n' "$f" >> "$FILE_LIST_TMP"
    fi
done < <(find outbox-spring/src/main/java -name "*.java" -print0 2>/dev/null)

if [[ ! -s "$FILE_LIST_TMP" ]]; then
    echo "check-since: no .java files found in exported packages." >&2
    exit 0
fi

# 3. For each file, check every public declaration has @since in its
#    Javadoc. Implemented in awk for portability.
VIOLATIONS=0
while IFS= read -r f; do
    [[ -z "$f" ]] && continue
    # The awk script returns offending line numbers + reasons on stderr.
    if ! out="$(awk -v FN="$f" '
        function trim(s) { sub(/^[[:space:]]+/, "", s); sub(/[[:space:]]+$/, "", s); return s }
        function is_public_decl(line,    t) {
            t = trim(line)
            # Skip lines that look like statements or annotations.
            if (t ~ /^@/) return 0
            if (t !~ /^public[[:space:]]/) return 0
            # Strip "public " prefix.
            sub(/^public[[:space:]]+/, "", t)
            # Allow modifier chains.
            while (t ~ /^(static|final|abstract|sealed|non-sealed|default|synchronized|native|strictfp)[[:space:]]/) {
                sub(/^[a-z-]+[[:space:]]+/, "", t)
            }
            # Skip record compact canonical constructor: "Identifier {".
            # These are auto-derived from the record header; do not require
            # their own @since.
            if (t ~ /^[A-Z][A-Za-z0-9_]*[[:space:]]*\{[[:space:]]*$/) return 0
            return 1
        }
        function count_chars(s, c,    n, i) {
            n = 0
            for (i = 1; i <= length(s); i++) if (substr(s, i, 1) == c) n++
            return n
        }
        BEGIN {
            in_block = 0
            javadoc_has_since = 0
            had_javadoc = 0
            has_override = 0
            in_annotation = 0
            paren_depth = 0
        }
        {
            line = $0
            # Track Javadoc blocks.
            if (line ~ /\/\*\*/) {
                in_block = 1
                javadoc_has_since = 0
                # Single-line javadoc /** ... */
                if (line ~ /\*\//) {
                    if (line ~ /@since/) javadoc_has_since = 1
                    in_block = 0
                    had_javadoc = 1
                }
                next
            }
            if (in_block == 1) {
                if (line ~ /@since/) javadoc_has_since = 1
                if (line ~ /\*\//) {
                    in_block = 0
                    had_javadoc = 1
                }
                next
            }
            # Skip blank lines.
            t = trim(line)
            if (t == "") next
            # If we are currently in a multi-line annotation, continue
            # consuming lines until the parenthesis depth drops back to 0.
            if (in_annotation == 1) {
                paren_depth += count_chars(line, "(") - count_chars(line, ")")
                if (paren_depth <= 0) {
                    in_annotation = 0
                    paren_depth = 0
                }
                next
            }
            # Track adjacent annotations between javadoc and the decl.
            if (t ~ /^@/) {
                if (t ~ /^@Override([^A-Za-z0-9_]|$)/) has_override = 1
                paren_depth = count_chars(line, "(") - count_chars(line, ")")
                if (paren_depth > 0) in_annotation = 1
                else paren_depth = 0
                next
            }

            if (is_public_decl(line)) {
                # @Override methods inherit @since from the supertype; do
                # not require their own Javadoc / @since tag.
                if (!has_override) {
                    if (!had_javadoc) {
                        printf "%s:%d: public declaration has no Javadoc: %s\n", FN, NR, t > "/dev/stderr"
                        bad = 1
                    } else if (!javadoc_has_since) {
                        printf "%s:%d: public declaration missing @since: %s\n", FN, NR, t > "/dev/stderr"
                        bad = 1
                    }
                }
            }
            # Once we cross a non-blank, non-annotation line, the preceding
            # javadoc no longer applies to anything else.
            had_javadoc = 0
            javadoc_has_since = 0
            has_override = 0
        }
        END { if (bad) exit 1 }
    ' "$f" 2>&1 1>/dev/null)"; then
        echo "$out" >&2
        VIOLATIONS=$((VIOLATIONS + 1))
    fi
done < "$FILE_LIST_TMP"

if [[ $VIOLATIONS -gt 0 ]]; then
    echo "" >&2
    echo "check-since: $VIOLATIONS file(s) had violations." >&2
    exit 1
fi

exit 0
