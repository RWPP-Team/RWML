/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml;

import io.github.rwml.core.DependencyResolution;
import io.github.rwml.core.ExtensionDescriptor;
import io.github.rwml.core.ExtensionVersions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 版本选择与版本约束的自动解析。 */
class ExtensionVersionResolutionTest {

    @Test
    void newestVersionSatisfyingTheDeclaredRangeWins() {
        DependencyResolution resolution = ExtensionVersions.resolve(List.of(
            extension("lib", "1.0", ""),
            extension("lib", "2.0", ""),
            extension("consumer", "1.0", "lib>=2")), Map.of());

        assertTrue(resolution.excluded().isEmpty());
        assertEquals(List.of("lib 2.0", "consumer 1.0"), versions(resolution));
    }

    @Test
    void impossibleRangeExcludesOnlyTheConsumer() {
        DependencyResolution resolution = ExtensionVersions.resolve(List.of(
            extension("lib", "1.0", ""),
            extension("consumer", "1.0", "lib>=2")), Map.of());

        assertEquals(List.of("lib 1.0"), versions(resolution));
        assertTrue(resolution.excluded().containsKey("consumer"));
        assertTrue(resolution.excluded().get("consumer").contains("lib>=2"));
    }

    @Test
    void mutuallyExclusiveRangesKeepTheVersionThatSatisfiesMoreExtensions() {
        DependencyResolution resolution = ExtensionVersions.resolve(List.of(
            extension("lib", "1.0", ""),
            extension("lib", "2.0", ""),
            extension("newer-a", "1.0", "lib>=2"),
            extension("newer-b", "1.0", "lib>=2"),
            extension("older", "1.0", "lib<2")), Map.of());

        assertEquals(List.of("lib 2.0", "newer-a 1.0", "newer-b 1.0"), versions(resolution));
        assertTrue(resolution.excluded().containsKey("older"));
    }

    @Test
    void explicitChoiceWinsEvenWhenItContradictsARequirement() {
        ExtensionDescriptor one = extension("lib", "1.0", "");
        DependencyResolution resolution = ExtensionVersions.resolve(List.of(
            one, extension("lib", "2.0", ""), extension("consumer", "1.0", "lib>=2")),
            Map.of("lib", ExtensionVersions.identity(one)));

        assertEquals(List.of("lib 1.0"), versions(resolution));
        assertTrue(resolution.excluded().containsKey("consumer"));
    }

    @Test
    void staleChoiceIsReportedRatherThanGuessed() {
        List<ExtensionDescriptor> descriptors = List.of(extension("lib", "1.0", ""), extension("lib", "2.0", ""));
        Map<String, String> stale = Map.of("lib", "digest-of-a-removed-archive");

        DependencyResolution resolution = ExtensionVersions.resolve(descriptors, stale);
        assertTrue(resolution.loadOrder().isEmpty());
        assertTrue(resolution.excluded().containsKey("lib"));
        assertTrue(ExtensionVersions.hasStaleSelection(descriptors, stale));

        // 只有一个候选时，记录的选择无关紧要，不必打扰用户。
        List<ExtensionDescriptor> single = List.of(extension("lib", "1.0", ""));
        assertEquals(List.of("lib 1.0"), versions(ExtensionVersions.resolve(single, stale)));
        assertFalse(ExtensionVersions.hasStaleSelection(single, stale));
    }

    @Test
    void preReleasesLoseToReleasesOfTheSameNumbers() {
        DependencyResolution resolution = ExtensionVersions.resolve(List.of(
            extension("lib", "2.0.0-beta", ""),
            extension("lib", "2.0.0", "")), Map.of());

        assertEquals(List.of("lib 2.0.0"), versions(resolution));
    }

    private static List<String> versions(DependencyResolution resolution) {
        List<String> result = new java.util.ArrayList<>();
        for (ExtensionDescriptor descriptor : resolution.loadOrder()) {
            result.add(descriptor.id() + " " + descriptor.version());
        }
        return result;
    }

    private static ExtensionDescriptor extension(String id, String version, String dependsOn) {
        return new ExtensionDescriptor(id, id, version, "", List.of(), "", "", "", "", List.of(), "1.0", 8,
            "example.Main", "", false, dependsOn.isEmpty() ? List.of() : List.of(dependsOn),
            List.of(), List.of(), Path.of(id + "-" + version + ".jar"), 0L, "");
    }
}
