/*
 * Copyright (c) 2026 Gustavo Lobo
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.lobofoltran.outbox.tck;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Requires;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * Smoke test that pins the {@code outbox-tck} module's public {@code requires transitive} edges.
 *
 * <p>External dialect authors {@code extends OutboxDialectContractTest} as a regular {@code
 * compile}-scope dependency. For that to work in a JPMS-modular consumer, every type that appears
 * in the contract base's signatures must be reachable transitively from the TCK module. This test
 * fails if any of those edges accidentally drop the {@code transitive} modifier or disappear
 * altogether.
 *
 * <p>The {@link ModuleDescriptor} API is public — no reflection bypass is used.
 */
class TckModuleInfoTest {

    /**
     * Loads the {@code module-info.class} for {@code outbox-tck} by locating the code source of
     * {@link OutboxDialectContractTest} and asking {@link ModuleFinder} to read the descriptor from
     * that exact location. This works whether Surefire runs the test on the module path (the
     * patched module's descriptor would be visible directly via {@link Class#getModule()}) or on
     * the classpath (where {@code getModule()} returns the unnamed module). Reading via {@code
     * getResourceAsStream("/module-info.class")} is unsafe because that traversal
     * non-deterministically picks the first {@code module-info.class} on the classpath, often a JDK
     * platform module unrelated to the module under test.
     */
    private static final ModuleDescriptor DESCRIPTOR = readDescriptor();

    private static ModuleDescriptor readDescriptor() {
        Path location;
        try {
            location =
                    Path.of(
                            OutboxDialectContractTest.class
                                    .getProtectionDomain()
                                    .getCodeSource()
                                    .getLocation()
                                    .toURI());
        } catch (URISyntaxException ex) {
            throw new IllegalStateException(
                    "outbox-tck code source URI is malformed; cannot locate module-info.class", ex);
        }
        ModuleReference reference =
                ModuleFinder.of(location).findAll().stream()
                        .filter(
                                ref ->
                                        ref.descriptor()
                                                .name()
                                                .equals("io.github.lobofoltran.outbox.tck"))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "ModuleFinder could not locate the outbox-tck"
                                                        + " module at "
                                                        + location));
        return reference.descriptor();
    }

    @Test
    void module_is_named() {
        assertThat(DESCRIPTOR).isNotNull();
        assertThat(DESCRIPTOR.name()).isEqualTo("io.github.lobofoltran.outbox.tck");
    }

    @Test
    void exports_the_tck_package() {
        Set<String> exportedPackages =
                DESCRIPTOR.exports().stream()
                        .map(ModuleDescriptor.Exports::source)
                        .collect(Collectors.toUnmodifiableSet());
        assertThat(exportedPackages).contains("io.github.lobofoltran.outbox.tck");
    }

    @Test
    void requires_outbox_core_transitively() {
        assertTransitive("io.github.lobofoltran.outbox.core");
    }

    @Test
    void requires_outbox_jdbc_transitively() {
        assertTransitive("io.github.lobofoltran.outbox.jdbc");
    }

    @Test
    void requires_java_sql_transitively() {
        assertTransitive("java.sql");
    }

    @Test
    void requires_junit_jupiter_api_transitively() {
        assertTransitive("org.junit.jupiter.api");
    }

    @Test
    void requires_assertj_core_transitively() {
        assertTransitive("org.assertj.core");
    }

    @Test
    void does_not_require_testcontainers_jpms_module() {
        // Pinned by design: the contract base does not reference Testcontainers in any signature,
        // and adding `requires testcontainers` would (a) fix a brittle filename-derived module
        // name on every consumer and (b) force the test compile of subclassers to add an explicit
        // `requires postgresql` for the `org.testcontainers:postgresql` JAR (different module).
        // Subclassers wire Testcontainers themselves. See `module-info.java` for the full
        // rationale.
        assertThat(
                        DESCRIPTOR.requires().stream()
                                .map(Requires::name)
                                .filter(n -> n.equals("testcontainers"))
                                .findAny())
                .isEmpty();
    }

    private static void assertTransitive(String moduleName) {
        Requires requires =
                DESCRIPTOR.requires().stream()
                        .filter(r -> r.name().equals(moduleName))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "outbox-tck module-info is missing `requires "
                                                        + moduleName
                                                        + "`"));
        assertThat(requires.modifiers())
                .as(
                        "outbox-tck must declare `requires transitive %s` so consumer modules"
                                + " inherit the type without redeclaring it",
                        moduleName)
                .contains(Requires.Modifier.TRANSITIVE);
        assertThat(requires.modifiers())
                .as("`requires %s` must not be `static`", moduleName)
                .doesNotContainAnyElementsOf(EnumSet.of(Requires.Modifier.STATIC));
    }
}
