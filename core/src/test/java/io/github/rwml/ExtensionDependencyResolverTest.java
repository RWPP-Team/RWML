/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml;

import io.github.rwml.core.DependencyResolution;
import io.github.rwml.core.ExtensionDependencyResolver;
import io.github.rwml.core.ExtensionDescriptor;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtensionDependencyResolverTest {

    private final ExtensionDependencyResolver resolver = new ExtensionDependencyResolver();

    @Test
    void dependenciesAreLoadedBeforeDependents() {
        DependencyResolution resolution = resolver.resolve(List.of(
            descriptor("b", List.of("a"), List.of(), List.of()),
            descriptor("a", List.of(), List.of(), List.of())));

        assertEquals(List.of("a", "b"), ids(resolution));
        assertTrue(resolution.excluded().isEmpty());
    }

    @Test
    void missingHardDependencyExcludesTheDependent() {
        DependencyResolution resolution = resolver.resolve(List.of(
            descriptor("b", List.of("missing"), List.of(), List.of()),
            descriptor("a", List.of(), List.of(), List.of())));

        assertEquals(List.of("a"), ids(resolution));
        assertEquals(Map.of("b", "Unresolved dependency 'missing'"), resolution.excluded());
    }

    @Test
    void exclusionCascadesThroughTheGraph() {
        DependencyResolution resolution = resolver.resolve(List.of(
            descriptor("a", List.of("missing"), List.of(), List.of()),
            descriptor("b", List.of("a"), List.of(), List.of())));

        assertTrue(resolution.loadOrder().isEmpty());
        assertTrue(resolution.excluded().containsKey("a"));
        assertEquals("Unresolved dependency 'a'", resolution.excluded().get("b"));
    }

    @Test
    void cyclesAreReportedInsteadOfHanging() {
        DependencyResolution resolution = resolver.resolve(List.of(
            descriptor("a", List.of("b"), List.of(), List.of()),
            descriptor("b", List.of("a"), List.of(), List.of())));

        assertTrue(resolution.loadOrder().isEmpty());
        assertEquals(2, resolution.excluded().size());
    }

    @Test
    void softOrderingHintsAreHonoured() {
        DependencyResolution resolution = resolver.resolve(List.of(
            descriptor("late", List.of(), List.of("early"), List.of()),
            descriptor("early", List.of(), List.of(), List.of("late"))));

        assertEquals(List.of("early", "late"), ids(resolution));
        assertTrue(resolution.excluded().isEmpty());
    }

    @Test
    void unknownSoftHintsAreIgnored() {
        DependencyResolution resolution = resolver.resolve(List.of(
            descriptor("a", List.of(), List.of("ghost"), List.of())));

        assertEquals(List.of("a"), ids(resolution));
        assertTrue(resolution.excluded().isEmpty());
    }

    @Test
    void selfDependencyIsRejected() {
        DependencyResolution resolution = resolver.resolve(List.of(
            descriptor("a", List.of("a"), List.of(), List.of())));

        assertTrue(resolution.loadOrder().isEmpty());
        assertFalse(resolution.excluded().isEmpty());
    }

    private static List<String> ids(DependencyResolution resolution) {
        List<String> ids = new ArrayList<>();
        resolution.loadOrder().forEach(descriptor -> ids.add(descriptor.id()));
        return ids;
    }

    private static ExtensionDescriptor descriptor(String id,
                                                  List<String> dependsOn,
                                                  List<String> loadAfter,
                                                  List<String> loadBefore) {
        return new ExtensionDescriptor(
            id, id, "1.0.0", "", List.of(), "", "", "", "", List.of(), "1.0", 8,
            "example.Main", "", dependsOn, loadBefore, loadAfter,
            Path.of(id + ".jar"), 0L, "");
    }
}
