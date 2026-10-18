/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class StubDexTest {

    private static final String GAME_ENGINE = "com.corrodinggames.rts.gameFramework.GameEngine";

    private final Path game = Paths.get(System.getProperty("rwml.testGameArchive"));

    @TempDir
    Path temporary;

    @Test
    void standsInOnlyForWhatThePlatformDoesNotSupply() {
        assertTrue(StubDex.standsIn("com/corrodinggames/rts/gameFramework/GameEngine"));
        // The archives carry the game and, for the desktop build, a copy of the Android API.
        assertFalse(StubDex.standsIn("android/graphics/PointF"));
        assertFalse(StubDex.standsIn("android/os/Parcelable"));
        // android/support rides along in the game's own dex, so nothing else supplies it.
        assertTrue(StubDex.standsIn("android/support/v4/app/Fragment"));
    }

    @Test
    void namesTheClassesOfTheArchivesInDottedForm() throws Exception {
        Set<String> names = StubDex.classNames(Collections.singletonList(game));
        assertTrue(names.contains(GAME_ENGINE), "the type the mixins patch has to be carried");
        assertTrue(names.stream().noneMatch(name -> name.startsWith("android.")
            && !name.startsWith("android.support.")), "a stand-in for the platform would shadow it");
        assertEquals(names.size(), names.stream().distinct().count());
    }

    @Test
    void compilesOneDexAndReusesIt() throws Exception {
        Path cache = temporary.resolve("stub-dex");
        List<Path> built = StubDex.build(Collections.singletonList(game), cache);
        try {
            assertEquals(1, built.size(), "one archive of stand-ins fits in one dex");
            assertTrue(Files.size(built.get(0)) > 0);
            assertEquals(built, StubDex.build(Collections.singletonList(game), cache),
                "a second build has to answer with the cached dex");
            assertFalse(Files.exists(cache.resolve("stub.jar")),
                "the class files handed to dx are deleted again");
        } finally {
            for (Path dex : built) {
                Dexer.makeWritable(dex);
            }
        }
    }
}
