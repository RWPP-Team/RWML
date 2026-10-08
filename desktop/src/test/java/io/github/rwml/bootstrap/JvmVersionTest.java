/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.bootstrap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bootstrap decides whether to restart the game purely from the JVM's reported version, so a
 * parsing mistake here means either an unnecessary download or a JVM that is too old to run RWML.
 * Both property formats the platform has used are therefore pinned down.
 */
class JvmVersionTest {

    @Test
    void parsesEveryVersionFormatTheJvmHasUsed() {
        assertEquals(8, JvmVersion.parseFeature("1.8"));
        assertEquals(8, JvmVersion.parseFeature("1.8.0_422"));
        assertEquals(9, JvmVersion.parseFeature("9"));
        assertEquals(11, JvmVersion.parseFeature("11.0.2"));
        assertEquals(17, JvmVersion.parseFeature("17"));
        assertEquals(25, JvmVersion.parseFeature("25.0.4.1"));
    }

    @Test
    void ignoresSuffixesAppendedToTheVersion() {
        assertEquals(17, JvmVersion.parseFeature("17-ea"));
        assertEquals(25, JvmVersion.parseFeature("25.0.4.1+1"));
        assertEquals(25, JvmVersion.parseFeature("  25.0.4.1  "));
    }

    @Test
    void reportsNothingRatherThanGuessing() {
        assertEquals(-1, JvmVersion.parseFeature(null));
        assertEquals(-1, JvmVersion.parseFeature(""));
        assertEquals(-1, JvmVersion.parseFeature("   "));
        assertEquals(-1, JvmVersion.parseFeature("not-a-version"));
    }

    @Test
    void readsTheRunningJvm() {
        assertTrue(JvmVersion.current() >= 8,
            "the test JVM should report a usable feature version, got " + JvmVersion.current());
    }
}
