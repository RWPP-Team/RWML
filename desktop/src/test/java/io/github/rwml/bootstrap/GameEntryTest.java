/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.bootstrap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * {@link GameEntry} is the naming contract between the build and the runtime, so its constants and
 * its fallbacks both matter: the packaged value wins when the jar was produced by
 * {@code packageGameJar}, and the compiled default has to apply when RWML runs unpackaged (tests, an
 * IDE, the demo harness).
 */
class GameEntryTest {

    @Test
    void fallsBackToTheCompiledDefaultWhenRunningUnpackaged() {
        // The test class path has no META-INF/rwml-bootstrap.properties, which is exactly the
        // unpackaged case. Nothing at runtime can change the answer: the target version comes from
        // the build that produced the jar, and from nowhere else.
        assertEquals(GameEntry.DEFAULT_TARGET_JAVA_VERSION, GameEntry.targetJavaVersion());
    }

    @Test
    void theTwoEntryPointsAreDistinctAndShareTheGamesPackage() {
        // RWML owns the name the launcher starts. The game's own entry point is a different class,
        // and it has to stay in the same package because the game reaches it through package-private
        // members.
        assertEquals("com.corrodinggames.rts.java.Main", GameEntry.MAIN);
        assertEquals("com.corrodinggames.rts.java.MainLauncher", GameEntry.GAME_MAIN);
        assertNotEquals(GameEntry.MAIN, GameEntry.GAME_MAIN);
        assertEquals(packageOf(GameEntry.MAIN), packageOf(GameEntry.GAME_MAIN));
    }

    private static String packageOf(String className) {
        return className.substring(0, className.lastIndexOf('.'));
    }
}
