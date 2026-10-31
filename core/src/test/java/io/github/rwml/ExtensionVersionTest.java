/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml;

import io.github.rwml.core.ExtensionRequirement;
import io.github.rwml.core.ExtensionVersion;
import io.github.rwml.core.VersionConstraint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtensionVersionTest {

    @Test
    void versionsCompareNumericallyAndTreatMissingSegmentsAsZero() {
        assertTrue(ExtensionVersion.compare("1.10.0", "1.9.9") > 0);
        assertTrue(ExtensionVersion.compare("2", "10") < 0);
        assertEquals(0, ExtensionVersion.compare("1.2", "1.2.0"));
        assertEquals(0, ExtensionVersion.compare("v1.2.3", "1.2.3"));
        assertEquals(0, ExtensionVersion.compare("1.2.3+build5", "1.2.3"));
    }

    @Test
    void preReleaseSuffixesOrderBelowReleaseAndPatchSuffixesAboveIt() {
        assertTrue(ExtensionVersion.compare("1.0.0-alpha", "1.0.0-beta") < 0);
        assertTrue(ExtensionVersion.compare("1.0.0-beta", "1.0.0-rc1") < 0);
        assertTrue(ExtensionVersion.compare("1.0.0-rc1", "1.0.0-rc2") < 0);
        assertTrue(ExtensionVersion.compare("1.0.0-rc2", "1.0.0") < 0);
        assertTrue(ExtensionVersion.compare("1.0.0", "1.0.0-sp1") < 0);
    }

    @Test
    void unparsableInputDegradesInsteadOfThrowing() {
        assertEquals(0, ExtensionVersion.compare("", "0"));
        assertEquals(0, ExtensionVersion.compare(null, "0.0.0"));
        assertTrue(ExtensionVersion.compare("1.2.3", "not-a-version") > 0);
    }

    @Test
    void wildcardAndOperatorsExpandedIntoRanges() {
        assertMatches(">=1.2", "1.2", "1.2.1", "2.0");
        assertMisses(">=1.2", "1.1.9");
        assertMatches(">1.2", "1.2.1");
        assertMisses(">1.2", "1.2");
        assertMatches("<2.0", "1.9.9");
        assertMisses("<2.0", "2.0");
        assertMatches("^1.2.3", "1.2.3", "1.9");
        assertMisses("^1.2.3", "2.0");
        assertMatches("^0.2.3", "0.2.9");
        assertMisses("^0.2.3", "0.3");
        assertMatches("~1.2.3", "1.2.9");
        assertMisses("~1.2.3", "1.3");
        assertMatches("1.2.x", "1.2.7");
        assertMisses("1.2.x", "1.3");
        assertMatches("1.2.*", "1.2.0");
        assertMatches("=1.2", "1.2.0");
        assertMisses("=1.2", "1.2.1");
        assertMatches("[1.2,2.0)", "1.2", "1.9");
        assertMisses("[1.2,2.0)", "2.0");
        assertMatches("(1.2,2.0]", "2.0");
        assertMisses("(1.2,2.0]", "1.2");
        assertMatches("[1.5,)", "99");
        assertMatches(">=1.2 <2.0", "1.5");
        assertMatches(">=1.2,<2.0", "1.5");
        assertMisses(">=1.2 <2.0", "2.1");
    }

    @Test
    void absentOrStarConstraintAcceptsEverythingIncludingPreReleases() {
        for (VersionConstraint constraint : List.of(VersionConstraint.ANY, VersionConstraint.parse(""),
            VersionConstraint.parse("*"))) {
            assertTrue(constraint.matches("0.0.1"), constraint.text());
            assertTrue(constraint.matches("3.0.0-beta"), constraint.text());
        }
    }

    @Test
    void unparsableConstraintNeverMatchesInsteadOfThrowing() {
        VersionConstraint broken = VersionConstraint.parse(">=");
        assertTrue(broken.invalid());
        assertFalse(broken.matches("1.0"));
        assertFalse(VersionConstraint.parse("[1.0").matches("1.0"));
    }

    @Test
    void dependencyExpressionsKeepConstraintsTogetherWithTheirId() {
        assertEquals(List.of("lib>=1.2", "other"), ExtensionRequirement.tokenize("lib >= 1.2, other"));
        assertEquals(List.of("lib>=1.2", "other"), ExtensionRequirement.tokenize("lib>=1.2 other"));
        assertEquals(List.of("a", "b"), ExtensionRequirement.tokenize("a, b"));
        assertEquals(List.of("a", "b"), ExtensionRequirement.tokenize("a b"));
        assertEquals(List.of("a[1.0,2.0)"), ExtensionRequirement.tokenize("a[1.0, 2.0)"));
        assertEquals(List.of("a>=1.2 <2.0"), ExtensionRequirement.tokenize("a>=1.2 <2.0"));
        assertTrue(ExtensionRequirement.tokenize("").isEmpty());
        assertTrue(ExtensionRequirement.tokenize(null).isEmpty());
    }

    @Test
    void dependencyRequirementsCarryTheirConstraint() {
        ExtensionRequirement plain = ExtensionRequirement.parse("lib");
        assertEquals("lib", plain.id());
        assertTrue(plain.isUnconstrained());
        assertTrue(plain.satisfiedBy("0.0.0"));

        ExtensionRequirement constrained = ExtensionRequirement.parse("lib>=2.0");
        assertEquals("lib", constrained.id());
        assertFalse(constrained.isUnconstrained());
        assertEquals("lib>=2.0", constrained.text());
        assertTrue(constrained.satisfiedBy("2.1"));
        assertFalse(constrained.satisfiedBy("1.9"));

        assertNull(ExtensionRequirement.parse(""));
        assertNull(ExtensionRequirement.parse(">=1.0"));
    }

    private static void assertMatches(String constraint, String... versions) {
        VersionConstraint parsed = VersionConstraint.parse(constraint);
        for (String version : versions) {
            assertTrue(parsed.matches(version), constraint + " should accept " + version);
        }
    }

    private static void assertMisses(String constraint, String... versions) {
        VersionConstraint parsed = VersionConstraint.parse(constraint);
        for (String version : versions) {
            assertFalse(parsed.matches(version), constraint + " should reject " + version);
        }
    }
}
